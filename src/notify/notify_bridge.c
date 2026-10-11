/*
 * Droidspaces notification bridge - core daemon
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Linux apps -> XFCE/D-Bus notification daemon -> [this process] ->
 * Android notification system, over the NDJSON protocol described in
 * notify_protocol.h.
 */

#include "notify_bridge.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/timerfd.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "notify_dbus.h"
#include "notify_log.h"
#include "tray_menu.h"
#include "tray_sni.h"
#include "tray_xembed.h"

/*
 * epoll_data tagging scheme.
 *
 * epoll_data_t is a union, so we can't safely mix a raw pointer (used for
 * client fds) with a "high bit of a 32-bit fd" trick (used for dbus fds):
 * the low 32 bits of an arbitrary heap pointer are not guaranteed to have
 * their high bit clear, which would make a client event misidentify as a
 * dbus event. Instead every registration uses the same 64-bit tagged
 * encoding, so dispatch only ever needs to check the tag bits.
 */
#define EPOLL_TAG_PLAIN 0ULL   /* value = the fd itself */
#define EPOLL_TAG_DBUS (1ULL << 62)   /* value = dbus watch fd */
#define EPOLL_TAG_CLIENT (1ULL << 63) /* value = index into st->clients[] */
#define EPOLL_TAG_MASK (EPOLL_TAG_DBUS | EPOLL_TAG_CLIENT)

static inline uint64_t epoll_tag_plain(int fd) {
  return (uint64_t)(uint32_t)fd;
}
static inline uint64_t epoll_tag_dbus(int fd) {
  return EPOLL_TAG_DBUS | (uint64_t)(uint32_t)fd;
}
static inline uint64_t epoll_tag_client(int index) {
  return EPOLL_TAG_CLIENT | (uint64_t)(uint32_t)index;
}

#define MAX_CLIENTS 4
#define MAX_EPOLL_EVENTS 32
#define CLIENT_INBUF_SIZE (NOTIFY_BRIDGE_MAX_LINE + 1)
#define CLIENT_OUTBUF_SIZE (256 * 1024)
#define DEDUP_RING_SIZE 32
#define ID_MAP_SIZE 256
#define DBUS_RECONNECT_MAX_BACKOFF_SEC 30
#define PING_INTERVAL_SEC 20
#define CLIENT_TIMEOUT_SEC 60

/* ---- small helpers ------------------------------------------------- */

static volatile sig_atomic_t g_shutdown = 0;
static volatile sig_atomic_t g_reload = 0;

static void on_sigterm(int sig) {
  (void)sig;
  g_shutdown = 1;
}

static void on_sighup(int sig) {
  (void)sig;
  g_reload = 1;
}

static void gen_boot_id(char *out, size_t out_len) {
  unsigned char raw[16] = {0};
  FILE *f = fopen("/dev/urandom", "re");
  if (f) {
    if (fread(raw, 1, sizeof(raw), f) != sizeof(raw)) {
      /* fall through to time-based fallback below */
    }
    fclose(f);
  }
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  snprintf(out, out_len, "%02x%02x%02x%02x-%lx-%lx", raw[0], raw[1], raw[2],
           raw[3], (unsigned long)ts.tv_sec, (unsigned long)ts.tv_nsec);
}

/* Very small string escaper - the only untrusted-length inputs we embed
 * into JSON are app_name/summary/body/icon captured from D-Bus, so this
 * only needs to cover control chars, quotes and backslashes. */
static void json_escape(const char *in, char *out, size_t out_size) {
  size_t o = 0;
  for (size_t i = 0; in[i] != '\0' && o + 2 < out_size; i++) {
    unsigned char c = (unsigned char)in[i];
    if (c == '"' || c == '\\') {
      out[o++] = '\\';
      out[o++] = (char)c;
    } else if (c == '\n') {
      out[o++] = '\\';
      out[o++] = 'n';
    } else if (c == '\r') {
      /* drop */
    } else if (c < 0x20) {
      /* skip other control chars */
    } else {
      out[o++] = (char)c;
    }
  }
  out[o] = '\0';
}

/* ---- dedup / id mapping --------------------------------------------- */

struct dedup_entry {
  char app[NOTIFY_DBUS_APP_NAME_MAX];
  char summary[NOTIFY_DBUS_TEXT_MAX];
  char body[NOTIFY_DBUS_TEXT_MAX];
  time_t seen_at;
  uint32_t bridge_id;
};

struct id_map_entry {
  uint32_t dbus_id;   /* the call-serial style id from notify_dbus.c */
  uint32_t bridge_id; /* what we told Android */
  char app[NOTIFY_DBUS_APP_NAME_MAX];
  time_t created_at;
};

struct client {
  int fd;
  bool in_use;
  bool hello_seen;
  time_t connected_at;
  time_t last_activity;
  char inbuf[CLIENT_INBUF_SIZE];
  size_t inlen;
  char outbuf[CLIENT_OUTBUF_SIZE];
  size_t outlen;
};

struct bridge_state {
  struct notify_bridge_config cfg;
  char config_path[512];
  char boot_id[48];

  int epfd;
  int listen_fd;      /* TCP loopback */
  int unix_listen_fd;  /* optional unix socket, -1 if disabled */
  int wake_pipe[2];    /* self-pipe: dbus watch-set changed */
  int timer_fd;

  struct notify_dbus_ctx *dbus;
  time_t dbus_next_retry;
  int dbus_backoff_sec;
  int dbus_watch_count;
  int dbus_watch_fds[NOTIFY_DBUS_MAX_WATCHES];

  struct client clients[MAX_CLIENTS];

  struct dedup_entry dedup_ring[DEDUP_RING_SIZE];
  int dedup_next;

  struct id_map_entry id_map[ID_MAP_SIZE];
  int id_map_next;

  struct tray_sni_ctx *tray;
  char tray_watcher_label[TRAY_SNI_OWNER_LABEL_MAX];
  bool tray_watcher_present;
  bool tray_xembed_checked;
  struct tray_xembed_status tray_xembed;
};

static struct bridge_state g_state; /* single instance per process */

static void dedup_ring_remember(struct bridge_state *st, const char *app,
                                 const char *summary, const char *body,
                                 uint32_t bridge_id) {
  struct dedup_entry *e = &st->dedup_ring[st->dedup_next];
  snprintf(e->app, sizeof(e->app), "%s", app);
  snprintf(e->summary, sizeof(e->summary), "%s", summary);
  snprintf(e->body, sizeof(e->body), "%s", body);
  e->seen_at = time(NULL);
  e->bridge_id = bridge_id;
  st->dedup_next = (st->dedup_next + 1) % DEDUP_RING_SIZE;
}

/*
 * Requirement: "Do not duplicate notifications endlessly." Some apps
 * (Firefox download popups, build tool progress spam, chat clients on
 * flaky D-Bus reconnects) re-send byte-identical notifications in quick
 * succession. If we saw the exact same app+summary+body within the
 * debounce window, treat it as the same notification instead of
 * forwarding a fresh one.
 */
static int dedup_ring_find_recent(struct bridge_state *st, const char *app,
                                   const char *summary, const char *body) {
  time_t now = time(NULL);
  const int debounce_sec = 3;
  for (int i = 0; i < DEDUP_RING_SIZE; i++) {
    struct dedup_entry *e = &st->dedup_ring[i];
    if (e->seen_at == 0)
      continue;
    if (now - e->seen_at > debounce_sec)
      continue;
    if (strcmp(e->app, app) == 0 && strcmp(e->summary, summary) == 0 &&
        strcmp(e->body, body) == 0) {
      return (int)e->bridge_id;
    }
  }
  return -1;
}

static void id_map_put(struct bridge_state *st, uint32_t dbus_id,
                        uint32_t bridge_id, const char *app) {
  struct id_map_entry *e = &st->id_map[st->id_map_next];
  e->dbus_id = dbus_id;
  e->bridge_id = bridge_id;
  snprintf(e->app, sizeof(e->app), "%s", app);
  e->created_at = time(NULL);
  st->id_map_next = (st->id_map_next + 1) % ID_MAP_SIZE;
}

static bool id_map_lookup_bridge_id(struct bridge_state *st,
                                     uint32_t dbus_id, uint32_t *bridge_id) {
  for (int i = 0; i < ID_MAP_SIZE; i++) {
    if (st->id_map[i].dbus_id == dbus_id && st->id_map[i].created_at != 0) {
      *bridge_id = st->id_map[i].bridge_id;
      return true;
    }
  }
  return false;
}

static bool id_map_lookup_dbus_id(struct bridge_state *st, uint32_t bridge_id,
                                   uint32_t *dbus_id) {
  for (int i = 0; i < ID_MAP_SIZE; i++) {
    if (st->id_map[i].bridge_id == bridge_id && st->id_map[i].created_at != 0) {
      *dbus_id = st->id_map[i].dbus_id;
      return true;
    }
  }
  return false;
}

/* ---- client output ---------------------------------------------------- */

static void client_queue_line(struct client *c, const char *json_line) {
  if (!c->in_use)
    return;
  size_t len = strlen(json_line);
  if (c->outlen + len + 1 >= CLIENT_OUTBUF_SIZE) {
    notify_log(NOTIFY_LOG_WARN,
               "client fd=%d output buffer full, dropping oldest data", c->fd);
    c->outlen = 0; /* best-effort recovery: drop backlog rather than the fd */
  }
  memcpy(c->outbuf + c->outlen, json_line, len);
  c->outlen += len;
  c->outbuf[c->outlen++] = '\n';
}

static void broadcast_line(struct bridge_state *st, const char *json_line) {
  for (int i = 0; i < MAX_CLIENTS; i++) {
    if (st->clients[i].in_use)
      client_queue_line(&st->clients[i], json_line);
  }
}

static void flush_client(struct bridge_state *st, struct client *c) {
  while (c->outlen > 0) {
    ssize_t n = send(c->fd, c->outbuf, c->outlen, MSG_NOSIGNAL);
    if (n < 0) {
      if (errno == EAGAIN || errno == EWOULDBLOCK)
        return;
      notify_log(NOTIFY_LOG_INFO, "client fd=%d write error: %s", c->fd,
                 strerror(errno));
      /* caller (main loop) closes on next error path */
      return;
    }
    memmove(c->outbuf, c->outbuf + n, c->outlen - (size_t)n);
    c->outlen -= (size_t)n;
  }
  (void)st;
}

/* ---- D-Bus event handlers ---------------------------------------------- */

static void handle_dbus_notify(const struct notify_dbus_event *ev,
                                void *user_data) {
  struct bridge_state *st = user_data;

  if (!notify_config_app_allowed(&st->cfg, ev->app_name)) {
    notify_log(NOTIFY_LOG_DEBUG, "filtered (app not allowed): %s - %s",
               ev->app_name, ev->summary);
    return;
  }
  if (!notify_config_level_allows(&st->cfg, ev->urgency)) {
    notify_log(NOTIFY_LOG_DEBUG, "filtered (urgency): %s - %s", ev->app_name,
               ev->summary);
    return;
  }

  uint32_t bridge_id;
  bool is_update = false;

  /* 1) explicit D-Bus replaces_id -> reuse the same bridge/Android id. */
  if (ev->replaces_id != 0 &&
      id_map_lookup_bridge_id(st, ev->replaces_id, &bridge_id)) {
    is_update = true;
  } else {
    /* 2) fuzzy dedup: identical content from the same app in the last
     *    few seconds. Covers apps that don't use replaces_id but still
     *    re-fire the same notification (the "don't duplicate endlessly"
     *    requirement). */
    int existing =
        dedup_ring_find_recent(st, ev->app_name, ev->summary, ev->body);
    if (existing >= 0) {
      bridge_id = (uint32_t)existing;
      is_update = true;
    } else {
      bridge_id = ev->bridge_id;
    }
  }

  id_map_put(st, ev->dbus_id, bridge_id, ev->app_name);
  dedup_ring_remember(st, ev->app_name, ev->summary, ev->body, bridge_id);

  char app_esc[NOTIFY_DBUS_APP_NAME_MAX * 2];
  char summary_esc[NOTIFY_DBUS_TEXT_MAX * 2];
  char body_esc[NOTIFY_DBUS_TEXT_MAX * 2];
  char icon_esc[NOTIFY_DBUS_ICON_MAX * 2];
  json_escape(ev->app_name, app_esc, sizeof(app_esc));
  json_escape(ev->summary, summary_esc, sizeof(summary_esc));
  json_escape(ev->body, body_esc, sizeof(body_esc));
  json_escape(ev->icon, icon_esc, sizeof(icon_esc));

  const char *urgency_str = ev->urgency == NOTIFY_URGENCY_LOW      ? "low"
                             : ev->urgency == NOTIFY_URGENCY_CRITICAL ? "critical"
                                                                      : "normal";

  char line[NOTIFY_BRIDGE_MAX_LINE];
  snprintf(line, sizeof(line),
           "{\"type\":\"notify\",\"id\":%u,\"replaces_id\":%u,"
           "\"app_name\":\"%s\",\"summary\":\"%s\",\"body\":\"%s\","
           "\"urgency\":\"%s\",\"icon\":\"%s\",\"timestamp\":%lld,"
           "\"is_update\":%s}",
           bridge_id, is_update ? bridge_id : 0u, app_esc, summary_esc,
           body_esc, urgency_str, icon_esc, (long long)ev->timestamp,
           is_update ? "true" : "false");

  broadcast_line(st, line);
  notify_log(NOTIFY_LOG_INFO, "%s notify #%u from %s: %s",
             is_update ? "updated" : "forwarded", bridge_id, ev->app_name,
             ev->summary);
}

static void handle_dbus_close(const struct notify_dbus_close_event *ev,
                               void *user_data) {
  struct bridge_state *st = user_data;
  uint32_t bridge_id;
  if (!id_map_lookup_bridge_id(st, ev->dbus_id, &bridge_id)) {
    /* Not something we ever forwarded (may predate this process, or be
     * for an app that was filtered out) - nothing to tell Android. */
    return;
  }

  const char *reason_str = ev->reason == NOTIFY_CLOSE_EXPIRED    ? "expired"
                            : ev->reason == NOTIFY_CLOSE_DISMISSED ? "dismissed"
                            : ev->reason == NOTIFY_CLOSE_BY_SENDER ? "closed"
                                                                    : "closed";
  char line[256];
  snprintf(line, sizeof(line), "{\"type\":\"close\",\"id\":%u,\"reason\":\"%s\"}",
           bridge_id, reason_str);
  broadcast_line(st, line);
  notify_log(NOTIFY_LOG_DEBUG, "close #%u (%s)", bridge_id, reason_str);
}

static void handle_dbus_watches_changed(void *user_data) {
  struct bridge_state *st = user_data;
  char byte = 1;
  ssize_t written = write(st->wake_pipe[1], &byte, 1);
  (void)written; /* best effort - epoll fd rebuild also happens on next tick */
}

/* ---- tray bridge (StatusNotifierItem) ----------------------------------
 *
 * Requirements covered here:
 *  - "Forward icon/app name/status/menu actions": tray_item_line() below.
 *  - "Recover after XFCE restart / container restart / Android app
 *    restart": tray_sni.c's reconcile-on-watcher-change handles the
 *    first two; send_tray_dump_to() handles the third by giving every
 *    freshly-hello'd client the full current item list, the same way
 *    notification "hello" doesn't need the app to remember anything.
 *  - "Local only, no external connections, no remote tray access": the
 *    tray bridge only ever talks to (a) the session D-Bus, which is
 *    local IPC, and (b) the same loopback/unix-socket clients as the
 *    notification bridge - no code path here opens any other network
 *    connection.
 */

static const char *tray_status_str(enum tray_item_status s) {
  switch (s) {
  case TRAY_STATUS_PASSIVE:
    return "passive";
  case TRAY_STATUS_NEEDS_ATTENTION:
    return "needs_attention";
  case TRAY_STATUS_ACTIVE:
  default:
    return "active";
  }
}

static void tray_item_line(const struct tray_sni_item *item, bool is_update,
                            char *out, size_t out_size) {
  char app_esc[TRAY_SNI_APP_ID_MAX * 2];
  char title_esc[TRAY_SNI_TEXT_MAX * 2];
  char tooltip_esc[TRAY_SNI_TEXT_MAX * 2];
  char icon_esc[TRAY_SNI_TEXT_MAX * 2];
  json_escape(item->app_id, app_esc, sizeof(app_esc));
  json_escape(item->title, title_esc, sizeof(title_esc));
  json_escape(item->tooltip, tooltip_esc, sizeof(tooltip_esc));
  json_escape(item->icon_name, icon_esc, sizeof(icon_esc));

  snprintf(out, out_size,
           "{\"type\":\"%s\",\"id\":%u,\"app_id\":\"%s\",\"title\":\"%s\","
           "\"status\":\"%s\",\"icon_name\":\"%s\",\"tooltip\":\"%s\","
           "\"has_menu\":%s,\"protocol\":\"sni\"}",
           is_update ? "tray_update" : "tray_item", item->bridge_id, app_esc,
           title_esc, tray_status_str(item->status), icon_esc, tooltip_esc,
           item->has_menu ? "true" : "false");
}

static void broadcast_tray_state(struct bridge_state *st) {
  char line[512];
  snprintf(line, sizeof(line),
           "{\"type\":\"tray_state\",\"protocol\":\"sni\","
           "\"watcher_owner\":\"%s\",\"notify_fallback\":%s}",
           st->tray_watcher_present ? st->tray_watcher_label : "none",
           notify_config_tray_forwarding_enabled(&st->cfg) &&
                   st->cfg.tray_mode == TRAY_MODE_BOTH
               ? "true"
               : "false");
  broadcast_line(st, line);
}

static void send_tray_dump_to(struct bridge_state *st, struct client *c) {
  if (!notify_config_tray_forwarding_enabled(&st->cfg) || !st->tray)
    return;

  char line[512];
  snprintf(line, sizeof(line),
           "{\"type\":\"tray_state\",\"protocol\":\"sni\","
           "\"watcher_owner\":\"%s\",\"notify_fallback\":%s}",
           st->tray_watcher_present ? st->tray_watcher_label : "none",
           st->cfg.tray_mode == TRAY_MODE_BOTH ? "true" : "false");
  client_queue_line(c, line);

  struct tray_sni_item items[TRAY_SNI_MAX_ITEMS];
  int n = tray_sni_list(st->tray, items, TRAY_SNI_MAX_ITEMS);
  for (int i = 0; i < n; i++) {
    char item_line[1536];
    tray_item_line(&items[i], false, item_line, sizeof(item_line));
    client_queue_line(c, item_line);
  }
}

static void handle_tray_item_added(const struct tray_sni_item *item,
                                    void *user_data) {
  struct bridge_state *st = user_data;
  if (!notify_config_tray_forwarding_enabled(&st->cfg))
    return;
  char line[1536];
  tray_item_line(item, false, line, sizeof(line));
  broadcast_line(st, line);
}

static void handle_tray_item_updated(const struct tray_sni_item *item,
                                      void *user_data) {
  struct bridge_state *st = user_data;
  if (!notify_config_tray_forwarding_enabled(&st->cfg))
    return;
  char line[1536];
  tray_item_line(item, true, line, sizeof(line));
  broadcast_line(st, line);
}

static void handle_tray_item_removed(uint32_t bridge_id, void *user_data) {
  struct bridge_state *st = user_data;
  if (!notify_config_tray_forwarding_enabled(&st->cfg))
    return;
  char line[128];
  snprintf(line, sizeof(line), "{\"type\":\"tray_remove\",\"id\":%u}",
           bridge_id);
  broadcast_line(st, line);
}

static void handle_tray_watcher_changed(const char *owner_label,
                                         void *user_data) {
  struct bridge_state *st = user_data;
  st->tray_watcher_present = strcmp(owner_label, "none") != 0;
  snprintf(st->tray_watcher_label, sizeof(st->tray_watcher_label), "%s",
           owner_label);
  if (notify_config_tray_forwarding_enabled(&st->cfg))
    broadcast_tray_state(st);
}

static void tray_start_if_needed(struct bridge_state *st) {
  if (st->tray || !st->cfg.tray_enabled || !st->dbus)
    return;

  if (!st->tray_xembed_checked) {
    tray_xembed_check(&st->tray_xembed);
    st->tray_xembed_checked = true;
    notify_log(NOTIFY_LOG_INFO, "tray: xembed check: %s",
               st->tray_xembed.owner_note);
  }

  struct tray_sni_callbacks cb = {
      .on_item_added = handle_tray_item_added,
      .on_item_updated = handle_tray_item_updated,
      .on_item_removed = handle_tray_item_removed,
      .on_watcher_changed = handle_tray_watcher_changed,
      .user_data = st,
  };
  st->tray = tray_sni_start(notify_dbus_raw_connection(st->dbus), cb);
  notify_log(NOTIFY_LOG_INFO, "tray: bridge started (mode=%d)",
             st->cfg.tray_mode);
}

static void tray_stop(struct bridge_state *st) {
  if (!st->tray)
    return;
  tray_sni_stop(st->tray);
  st->tray = NULL;
  st->tray_watcher_present = false;
  st->tray_watcher_label[0] = '\0';
}

/* ---- reconnect logic for the D-Bus side --------------------------------
 *
 * Requirement: "Handle Linux notification daemon restart." The bridge
 * doesn't own org.freedesktop.Notifications, so xfce4-notifyd (or
 * whichever daemon) restarting doesn't disturb our session bus
 * connection or match rules at all - we keep receiving Notify() calls
 * uninterrupted. What *can* drop is the session bus itself (X session
 * restart, container network namespace churn) - that's what this
 * reconnect loop covers, with capped exponential backoff so a
 * permanently-unavailable bus doesn't spin the CPU.
 */

static void dbus_try_connect(struct bridge_state *st) {
  struct notify_dbus_callbacks cb = {
      .on_notify = handle_dbus_notify,
      .on_close = handle_dbus_close,
      .on_watches_changed = handle_dbus_watches_changed,
      .user_data = st,
  };
  st->dbus = notify_dbus_connect(cb);
  if (st->dbus) {
    notify_log(NOTIFY_LOG_INFO, "dbus: connection established");
    st->dbus_backoff_sec = 1;
    tray_start_if_needed(st);
  } else {
    st->dbus_backoff_sec = st->dbus_backoff_sec == 0
                               ? 1
                               : st->dbus_backoff_sec * 2;
    if (st->dbus_backoff_sec > DBUS_RECONNECT_MAX_BACKOFF_SEC)
      st->dbus_backoff_sec = DBUS_RECONNECT_MAX_BACKOFF_SEC;
    st->dbus_next_retry = time(NULL) + st->dbus_backoff_sec;
    notify_log(NOTIFY_LOG_WARN, "dbus: connect failed, retrying in %ds",
               st->dbus_backoff_sec);
  }
}

static void dbus_check_health(struct bridge_state *st) {
  if (st->dbus && !notify_dbus_is_connected(st->dbus)) {
    notify_log(NOTIFY_LOG_WARN, "dbus: connection lost, will reconnect");
    tray_stop(st); /* tray_sni holds a reference to the old connection */
    notify_dbus_disconnect(st->dbus);
    st->dbus = NULL;
    st->dbus_next_retry = time(NULL) + 1;
  }
  if (!st->dbus && time(NULL) >= st->dbus_next_retry)
    dbus_try_connect(st);
  if (st->tray)
    tray_sni_tick(st->tray);
}

/* ---- listening sockets --------------------------------------------------
 *
 * TCP on loopback is the primary transport: with net_mode=host the
 * Android app (running outside the container's namespace but sharing
 * the device's loopback) can reach 127.0.0.1:<port> directly. The unix
 * socket is offered for setups where the app is bridged in via a bind
 * mount into the container's /run instead (e.g. NAT net_mode, where
 * loopback isn't shared) - see the D-Bus integration notes at the end
 * of this change for how each maps to a real deployment.
 */

static int make_tcp_listener(int port) {
  int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return -1;
  int one = 1;
  setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = htons((uint16_t)port);

  if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
      listen(fd, 4) != 0) {
    close(fd);
    return -1;
  }
  return fd;
}

static int make_unix_listener(const char *path) {
  if (!path || !*path)
    return -1;

  unlink(path);

  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return -1;

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);

  if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
      listen(fd, 4) != 0) {
    close(fd);
    return -1;
  }
  chmod(path, 0660);
  return fd;
}

/* ---- inbound NDJSON message handling (Android -> daemon) --------------- */

/* Extremely small tolerant field extractor: looks for "key":value or
 * "key":"value" and copies it out. Good enough for the flat, known
 * message shapes this protocol uses; a real JSON parser would be
 * overkill for a handful of scalar fields. */
static bool json_find_string(const char *json, const char *key, char *out,
                              size_t out_size) {
  char needle[64];
  snprintf(needle, sizeof(needle), "\"%s\"", key);
  const char *p = strstr(json, needle);
  if (!p)
    return false;
  p = strchr(p + strlen(needle), ':');
  if (!p)
    return false;
  p++;
  while (*p == ' ')
    p++;
  if (*p != '"')
    return false;
  p++;
  size_t o = 0;
  while (*p && *p != '"' && o + 1 < out_size) {
    if (*p == '\\' && p[1]) {
      p++;
    }
    out[o++] = *p++;
  }
  out[o] = '\0';
  return true;
}

static bool json_find_uint(const char *json, const char *key,
                            uint32_t *out) {
  char needle[64];
  snprintf(needle, sizeof(needle), "\"%s\"", key);
  const char *p = strstr(json, needle);
  if (!p)
    return false;
  p = strchr(p + strlen(needle), ':');
  if (!p)
    return false;
  p++;
  while (*p == ' ')
    p++;
  *out = (uint32_t)strtoul(p, NULL, 10);
  return true;
}

static void handle_client_line(struct bridge_state *st, struct client *c,
                                const char *line) {
  char type[32];
  if (!json_find_string(line, "type", type, sizeof(type)))
    return;

  c->last_activity = time(NULL);

  if (strcmp(type, "hello") == 0) {
    c->hello_seen = true;
    char device[128] = "unknown";
    json_find_string(line, "device", device, sizeof(device));
    notify_log(NOTIFY_LOG_INFO, "app connected (fd=%d, device=%s)", c->fd,
               device);
  } else if (strcmp(type, "pong") == 0) {
    /* liveness confirmed, nothing else to do */
  } else if (strcmp(type, "dismiss") == 0) {
    uint32_t bridge_id;
    if (json_find_uint(line, "id", &bridge_id)) {
      uint32_t dbus_id;
      if (id_map_lookup_dbus_id(st, bridge_id, &dbus_id) && st->dbus) {
        notify_dbus_close_notification(st->dbus, dbus_id);
        notify_log(NOTIFY_LOG_INFO,
                   "dismiss #%u from Android -> closed on Linux side",
                   bridge_id);
      } else {
        notify_log(NOTIFY_LOG_DEBUG,
                   "dismiss #%u: no live D-Bus mapping (daemon restarted "
                   "or already closed) - ignoring",
                   bridge_id);
      }
    }
  } else if (strcmp(type, "click") == 0) {
    uint32_t bridge_id;
    if (json_find_uint(line, "id", &bridge_id)) {
      char action_key[64] = "default";
      json_find_string(line, "action_key", action_key, sizeof(action_key));
      uint32_t dbus_id;
      if (id_map_lookup_dbus_id(st, bridge_id, &dbus_id) && st->dbus) {
        notify_dbus_invoke_action(st->dbus, dbus_id, action_key);
        notify_log(NOTIFY_LOG_INFO, "click #%u (%s) replayed to Linux side",
                   bridge_id, action_key);
      }
    }
  } else if (strcmp(type, "tray_activate") == 0 ||
             strcmp(type, "tray_secondary_activate") == 0) {
    uint32_t bridge_id;
    if (json_find_uint(line, "id", &bridge_id) && st->tray) {
      bool secondary = strcmp(type, "tray_secondary_activate") == 0;
      tray_sni_activate(st->tray, bridge_id, secondary);
      notify_log(NOTIFY_LOG_INFO, "tray: %sctivate #%u from Android",
                 secondary ? "secondary a" : "a", bridge_id);
    }
  } else if (strcmp(type, "tray_menu_request") == 0) {
    uint32_t bridge_id;
    struct tray_sni_item item;
    if (json_find_uint(line, "id", &bridge_id) && st->tray &&
        tray_sni_lookup(st->tray, bridge_id, &item) && item.has_menu) {
      struct tray_menu_result result;
      if (tray_menu_fetch(notify_dbus_raw_connection(st->dbus), item.bus_name,
                           item.menu_object_path, &result)) {
        char line_buf[NOTIFY_BRIDGE_MAX_LINE];
        int off = snprintf(line_buf, sizeof(line_buf),
                            "{\"type\":\"tray_menu\",\"id\":%u,\"items\":[",
                            bridge_id);
        for (int i = 0; i < result.count && off > 0 &&
                        (size_t)off < sizeof(line_buf) - 1;
             i++) {
          char label_esc[sizeof(result.entries[i].label) * 2];
          json_escape(result.entries[i].label, label_esc, sizeof(label_esc));
          off += snprintf(
              line_buf + off, sizeof(line_buf) - (size_t)off,
              "%s{\"action_id\":%d,\"label\":\"%s\",\"enabled\":%s,"
              "\"checked\":%s,\"separator\":%s}",
              i == 0 ? "" : ",", result.entries[i].action_id, label_esc,
              result.entries[i].enabled ? "true" : "false",
              result.entries[i].checked ? "true" : "false",
              result.entries[i].separator ? "true" : "false");
        }
        if (off > 0 && (size_t)off < sizeof(line_buf) - 2)
          snprintf(line_buf + off, sizeof(line_buf) - (size_t)off, "]}");
        client_queue_line(c, line_buf);
        flush_client(st, c);
      }
    }
  } else if (strcmp(type, "tray_menu_action") == 0) {
    uint32_t bridge_id;
    uint32_t action_id;
    struct tray_sni_item item;
    if (json_find_uint(line, "id", &bridge_id) &&
        json_find_uint(line, "action_id", &action_id) && st->tray &&
        tray_sni_lookup(st->tray, bridge_id, &item) && item.has_menu) {
      tray_menu_invoke(notify_dbus_raw_connection(st->dbus), item.bus_name,
                        item.menu_object_path, (int32_t)action_id);
      notify_log(NOTIFY_LOG_INFO, "tray: menu action %u on #%u from Android",
                 action_id, bridge_id);
    }
  }
}

static void client_process_inbuf(struct bridge_state *st, struct client *c) {
  for (;;) {
    char *nl = memchr(c->inbuf, '\n', c->inlen);
    if (!nl)
      break;
    *nl = '\0';
    handle_client_line(st, c, c->inbuf);
    size_t consumed = (size_t)(nl - c->inbuf) + 1;
    memmove(c->inbuf, c->inbuf + consumed, c->inlen - consumed);
    c->inlen -= consumed;
  }
  if (c->inlen >= sizeof(c->inbuf) - 1) {
    notify_log(NOTIFY_LOG_WARN, "client fd=%d sent an oversized line, "
                                 "dropping buffer", c->fd);
    c->inlen = 0;
  }
}

/* ---- client lifecycle ---------------------------------------------- */

static struct client *find_free_client(struct bridge_state *st) {
  for (int i = 0; i < MAX_CLIENTS; i++)
    if (!st->clients[i].in_use)
      return &st->clients[i];
  return NULL;
}

static void close_client(struct bridge_state *st, struct client *c) {
  epoll_ctl(st->epfd, EPOLL_CTL_DEL, c->fd, NULL);
  close(c->fd);
  notify_log(NOTIFY_LOG_INFO, "app disconnected (fd=%d)", c->fd);
  memset(c, 0, sizeof(*c));
}

static void send_hello(struct bridge_state *st, struct client *c) {
  char line[256];
  snprintf(line, sizeof(line),
           "{\"type\":\"hello\",\"proto_version\":%d,\"daemon_pid\":%d,"
           "\"boot_id\":\"%s\"}",
           NOTIFY_BRIDGE_PROTO_VERSION, (int)getpid(), st->boot_id);
  client_queue_line(c, line);
  send_tray_dump_to(st, c);
}

static void accept_clients(struct bridge_state *st, int listen_fd) {
  for (;;) {
    int fd = accept4(listen_fd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
    if (fd < 0)
      return;

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    struct client *c = find_free_client(st);
    if (!c) {
      notify_log(NOTIFY_LOG_WARN,
                 "client limit (%d) reached, rejecting connection",
                 MAX_CLIENTS);
      close(fd);
      continue;
    }

    memset(c, 0, sizeof(*c));
    c->fd = fd;
    c->in_use = true;
    c->connected_at = time(NULL);
    c->last_activity = c->connected_at;

    int idx = (int)(c - st->clients);
    struct epoll_event ev = {.events = EPOLLIN,
                              .data = {.u64 = epoll_tag_client(idx)}};
    epoll_ctl(st->epfd, EPOLL_CTL_ADD, fd, &ev);

    send_hello(st, c);
    flush_client(st, c);
    notify_log(NOTIFY_LOG_INFO, "app connecting (fd=%d)", fd);
  }
}

/* ---- dbus fd bookkeeping in epoll ------------------------------------ */

static void rebuild_dbus_epoll_set(struct bridge_state *st) {
  for (int i = 0; i < st->dbus_watch_count; i++)
    epoll_ctl(st->epfd, EPOLL_CTL_DEL, st->dbus_watch_fds[i], NULL);
  st->dbus_watch_count = 0;

  if (!st->dbus)
    return;

  struct notify_dbus_pollfd pfds[NOTIFY_DBUS_MAX_WATCHES];
  int n = notify_dbus_pollfds(st->dbus, pfds);
  for (int i = 0; i < n; i++) {
    uint32_t events = 0;
    if (pfds[i].want_read)
      events |= EPOLLIN;
    if (pfds[i].want_write)
      events |= EPOLLOUT;
    struct epoll_event ev = {.events = events,
                              .data = {.u64 = epoll_tag_dbus(pfds[i].fd)}};
    if (epoll_ctl(st->epfd, EPOLL_CTL_ADD, pfds[i].fd, &ev) == 0)
      st->dbus_watch_fds[st->dbus_watch_count++] = pfds[i].fd;
  }
}

/* ---- pidfile + config reload ------------------------------------------ */

static void write_pidfile(const char *path) {
  if (!path)
    return;
  FILE *f = fopen(path, "we");
  if (!f)
    return;
  fprintf(f, "%d\n", (int)getpid());
  fclose(f);
}

static void apply_config_reload(struct bridge_state *st) {
  bool was_enabled = st->cfg.bridge_enabled;
  bool tray_was_enabled = st->cfg.tray_enabled;
  notify_config_reload(&st->cfg);
  notify_log(NOTIFY_LOG_INFO,
             "config reloaded (SIGHUP): bridge=%s level=%d apps=%d "
             "tray=%s tray_mode=%d",
             st->cfg.bridge_enabled ? "enabled" : "disabled", st->cfg.level,
             st->cfg.app_count, st->cfg.tray_enabled ? "enabled" : "disabled",
             st->cfg.tray_mode);

  if (was_enabled && !st->cfg.bridge_enabled) {
    notify_log(NOTIFY_LOG_INFO,
               "NOTIFICATION_BRIDGE turned off - notifications will stop "
               "being forwarded (daemon keeps running, listening for "
               "config to re-enable)");
  }

  if (st->cfg.tray_enabled && !tray_was_enabled) {
    tray_start_if_needed(st);
  } else if (!st->cfg.tray_enabled && tray_was_enabled) {
    tray_stop(st);
    notify_log(NOTIFY_LOG_INFO,
               "TRAY_BRIDGE turned off - tray watching stopped");
  }
  /* TRAY_MODE alone (xfce/android/both) needs no start/stop action here:
   * watching stays warm regardless, and every tray_* broadcast path is
   * gated through notify_config_tray_forwarding_enabled() at send time. */
}

/* ---- main loop --------------------------------------------------------- */

int notify_bridge_run(const struct notify_bridge_opts *opts) {
  struct bridge_state *st = &g_state;
  memset(st, 0, sizeof(*st));

  const char *config_path =
      (opts && opts->config_path) ? opts->config_path : NOTIFY_CFG_DEFAULT_PATH;
  snprintf(st->config_path, sizeof(st->config_path), "%s", config_path);
  notify_config_load(st->config_path, &st->cfg);

  notify_log_open(st->cfg.log_path);
  gen_boot_id(st->boot_id, sizeof(st->boot_id));
  notify_log(NOTIFY_LOG_INFO, "droidspaces-notifyd starting, boot_id=%s",
             st->boot_id);

  if (!st->cfg.bridge_enabled) {
    notify_log(NOTIFY_LOG_WARN,
               "NOTIFICATION_BRIDGE is not enabled in %s - the daemon will "
               "idle and watch the config file for changes (SIGHUP or "
               "restart to pick up NOTIFICATION_BRIDGE=true)",
               st->config_path);
  }

  write_pidfile(opts && opts->pid_file ? opts->pid_file
                                        : "/run/droidspaces/notify-bridge.pid");

  signal(SIGPIPE, SIG_IGN);
  signal(SIGTERM, on_sigterm);
  signal(SIGINT, on_sigterm);
  signal(SIGHUP, on_sighup);

  st->epfd = epoll_create1(EPOLL_CLOEXEC);

  if (pipe2(st->wake_pipe, O_NONBLOCK | O_CLOEXEC) != 0) {
    notify_log(NOTIFY_LOG_ERROR, "pipe2 failed: %s", strerror(errno));
    return 1;
  }
  struct epoll_event wake_ev = {
      .events = EPOLLIN, .data = {.u64 = epoll_tag_plain(st->wake_pipe[0])}};
  epoll_ctl(st->epfd, EPOLL_CTL_ADD, st->wake_pipe[0], &wake_ev);

  st->listen_fd = make_tcp_listener(st->cfg.listen_port);
  if (st->listen_fd < 0) {
    notify_log(NOTIFY_LOG_ERROR, "failed to bind 127.0.0.1:%d: %s",
               st->cfg.listen_port, strerror(errno));
  } else {
    struct epoll_event ev = {.events = EPOLLIN,
                              .data = {.u64 = epoll_tag_plain(st->listen_fd)}};
    epoll_ctl(st->epfd, EPOLL_CTL_ADD, st->listen_fd, &ev);
    notify_log(NOTIFY_LOG_INFO, "listening on 127.0.0.1:%d",
               st->cfg.listen_port);
  }

  st->unix_listen_fd = make_unix_listener(st->cfg.socket_path);
  if (st->unix_listen_fd >= 0) {
    struct epoll_event ev = {
        .events = EPOLLIN,
        .data = {.u64 = epoll_tag_plain(st->unix_listen_fd)}};
    epoll_ctl(st->epfd, EPOLL_CTL_ADD, st->unix_listen_fd, &ev);
    notify_log(NOTIFY_LOG_INFO, "listening on unix socket %s",
               st->cfg.socket_path);
  }

  st->timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
  struct itimerspec its = {.it_interval = {.tv_sec = 1, .tv_nsec = 0},
                            .it_value = {.tv_sec = 1, .tv_nsec = 0}};
  timerfd_settime(st->timer_fd, 0, &its, NULL);
  struct epoll_event timer_ev = {
      .events = EPOLLIN, .data = {.u64 = epoll_tag_plain(st->timer_fd)}};
  epoll_ctl(st->epfd, EPOLL_CTL_ADD, st->timer_fd, &timer_ev);

  st->dbus_backoff_sec = 0;
  st->dbus_next_retry = 0; /* connect immediately on first tick */

  struct epoll_event events[MAX_EPOLL_EVENTS];

  while (!g_shutdown) {
    if (g_reload) {
      g_reload = 0;
      apply_config_reload(st);
    }

    int nfds = epoll_wait(st->epfd, events, MAX_EPOLL_EVENTS, 1000);
    if (nfds < 0) {
      if (errno == EINTR)
        continue;
      notify_log(NOTIFY_LOG_ERROR, "epoll_wait: %s", strerror(errno));
      break;
    }

    bool dbus_activity = false;

    for (int i = 0; i < nfds; i++) {
      struct epoll_event *ev = &events[i];
      uint64_t tag = ev->data.u64 & EPOLL_TAG_MASK;
      uint32_t value = (uint32_t)(ev->data.u64 & 0xFFFFFFFFu);

      if (tag == EPOLL_TAG_CLIENT) {
        struct client *c = &st->clients[value];
        if (!c->in_use)
          continue; /* stale event for an fd we already closed this tick */
        if (ev->events & (EPOLLHUP | EPOLLERR)) {
          close_client(st, c);
          continue;
        }
        if (ev->events & EPOLLIN) {
          ssize_t n = recv(c->fd, c->inbuf + c->inlen,
                            sizeof(c->inbuf) - c->inlen - 1, 0);
          if (n <= 0) {
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
              continue;
            close_client(st, c);
            continue;
          }
          c->inlen += (size_t)n;
          c->inbuf[c->inlen] = '\0';
          client_process_inbuf(st, c);
        }
        if (ev->events & EPOLLOUT) {
          flush_client(st, c);
        }
        continue;
      }

      if (tag == EPOLL_TAG_DBUS) {
        dbus_activity = true;
        continue;
      }

      /* tag == EPOLL_TAG_PLAIN: one of our fixed listener/utility fds. */
      int fd = (int)value;
      if (fd == st->timer_fd) {
        uint64_t ticks;
        ssize_t rd = read(st->timer_fd, &ticks, sizeof(ticks));
        (void)rd;
        dbus_check_health(st);

        time_t now = time(NULL);
        for (int ci = 0; ci < MAX_CLIENTS; ci++) {
          struct client *c = &st->clients[ci];
          if (!c->in_use)
            continue;
          if (now - c->last_activity > CLIENT_TIMEOUT_SEC) {
            notify_log(NOTIFY_LOG_INFO,
                       "client fd=%d timed out (no traffic for %ds), "
                       "closing so it can reconnect",
                       c->fd, CLIENT_TIMEOUT_SEC);
            close_client(st, c);
            continue;
          }
          if (now - c->last_activity > 0 &&
              (now - c->last_activity) % PING_INTERVAL_SEC == 0) {
            client_queue_line(c, "{\"type\":\"ping\"}");
            flush_client(st, c);
          }
        }
        continue;
      }

      if (fd == st->wake_pipe[0]) {
        char buf[64];
        while (read(st->wake_pipe[0], buf, sizeof(buf)) > 0) {
        }
        rebuild_dbus_epoll_set(st);
        continue;
      }

      if (fd == st->listen_fd) {
        accept_clients(st, st->listen_fd);
        continue;
      }
      if (fd == st->unix_listen_fd) {
        accept_clients(st, st->unix_listen_fd);
        continue;
      }
    }

    if (dbus_activity && st->dbus)
      notify_dbus_process(st->dbus);
  }

  notify_log(NOTIFY_LOG_INFO, "shutting down");
  tray_stop(st);
  if (st->dbus)
    notify_dbus_disconnect(st->dbus);
  for (int i = 0; i < MAX_CLIENTS; i++)
    if (st->clients[i].in_use)
      close_client(st, &st->clients[i]);
  if (st->listen_fd >= 0)
    close(st->listen_fd);
  if (st->unix_listen_fd >= 0)
    close(st->unix_listen_fd);
  close(st->timer_fd);
  close(st->wake_pipe[0]);
  close(st->wake_pipe[1]);
  close(st->epfd);
  notify_log_close();
  return 0;
}
