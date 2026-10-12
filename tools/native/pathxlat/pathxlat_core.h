/*
 * pathxlat_core: in-process guest<->host path translation for the native-mode
 * spike, matching the longest-prefix bind semantics of
 * tools/proot/fastpath/fastpath.c (to_host / bind_of / to_guest / under).
 *
 * The rootfs lives at ROOT (not at /).  A guest absolute path is mapped to the
 * host path proot would give the kernel:
 *   - the longest bind whose guest path is a prefix of it (on a component
 *     boundary) wins; of two binds with the same guest path, the LAST wins
 *     (proot keeps the last); a bind's host replaces that guest prefix;
 *   - otherwise ROOT is prepended.
 * Identity binds (host == guest, e.g. /storage/emulated/0, files/, cache/)
 * fall out of the same rule.  Nested override binds (an SD-library private dir
 * bound inside a parent bind) are handled by longest-prefix selection.
 *
 * Detranslation (host -> guest, for getcwd/readlink results) picks the deepest
 * host-prefix bind, or strips ROOT; if two guest names map to the same host
 * path it is ambiguous and the caller should leave the value untranslated
 * (proot's behaviour is list-order dependent and not reproduced here).
 *
 * This is PREFIX translation, not fastpath.c's full per-component symlink
 * walk: a relocated-rootfs glibc translates the literal path it is handed and
 * lets the kernel resolve symlinks within ROOT.  The divergences (a symlink
 * whose target crosses a bind, "..") are listed in 04-spikes.md.
 *
 * Header-only and freestanding (no malloc, no libc path calls) so it can be
 * compiled both into a preload and, unchanged in spirit, into glibc's ld.so,
 * which runs before any preload.  Spike code.
 */
#ifndef DROIDDECK_PATHXLAT_CORE_H
#define DROIDDECK_PATHXLAT_CORE_H

#include <stddef.h>
#include <string.h>
#include <stdio.h>

#ifndef PX_PATH_MAX
#define PX_PATH_MAX 4096
#endif
#ifndef PX_MAX_BINDS
#define PX_MAX_BINDS 256
#endif

struct px_bind { const char *host; size_t hlen; const char *guest; size_t glen; };

struct px_table {
  char root[PX_PATH_MAX];
  size_t rootlen;
  struct px_bind binds[PX_MAX_BINDS];
  int nbinds;
  /* backing storage for parsed strings */
  char store[PX_MAX_BINDS * 2 * 512];
  size_t store_used;
  int ready;
};

/* Component-boundary prefix test: is `path` under directory `dir` (length
   dlen)?  "/" (dlen 1) is under-everything for absolutes.  Mirrors
   fastpath.c:under(). */
static inline int px_under(const char *path, const char *dir, size_t dlen)
{
  if (dlen == 1 && dir[0] == '/') return path[0] == '/';
  return strncmp(path, dir, dlen) == 0 && (path[dlen] == 0 || path[dlen] == '/');
}

static inline char *px_dup(struct px_table *t, const char *s, size_t n)
{
  if (t->store_used + n + 1 > sizeof t->store) return NULL;
  char *p = t->store + t->store_used;
  memcpy(p, s, n);
  p[n] = 0;
  t->store_used += n + 1;
  return p;
}

/* Parse ROOT and a "host:guest|host:guest|..." bind spec into the table.
   Returns 0 on success, -1 on overflow/malformed.  A bind with no ':' is an
   identity bind (host == guest). */
static inline int px_parse(struct px_table *t, const char *root, const char *binds)
{
  memset(t, 0, sizeof *t);
  if (!root || !*root) return -1;
  size_t rl = strlen(root);
  if (rl >= sizeof t->root) return -1;
  memcpy(t->root, root, rl + 1);
  while (rl > 1 && t->root[rl - 1] == '/') t->root[--rl] = 0; /* no trailing / */
  t->rootlen = rl;

  const char *s = binds;
  while (s && *s && t->nbinds < PX_MAX_BINDS) {
    const char *end = strchr(s, '|');
    size_t len = end ? (size_t)(end - s) : strlen(s);
    if (len > 0) {
      const char *colon = memchr(s, ':', len);
      const char *hs, *gs; size_t hn, gn;
      if (colon) { hs = s; hn = colon - s; gs = colon + 1; gn = (s + len) - gs; }
      else       { hs = gs = s; hn = gn = len; }           /* identity bind */
      if (gn >= 2 && gs[0] == '/' && hn >= 1) {
        struct px_bind *b = &t->binds[t->nbinds];
        b->host = px_dup(t, hs, hn);
        b->guest = px_dup(t, gs, gn);
        if (!b->host || !b->guest) return -1;
        b->hlen = hn; b->glen = gn;
        /* drop a trailing slash on guest/host for clean boundary matching */
        while (b->glen > 1 && b->guest[b->glen - 1] == '/') ((char *)b->guest)[--b->glen] = 0;
        while (b->hlen > 1 && b->host[b->hlen - 1] == '/') ((char *)b->host)[--b->hlen] = 0;
        t->nbinds++;
      }
    }
    s = end ? end + 1 : NULL;
  }
  t->ready = 1;
  return 0;
}

/* The bind a canonical guest path is under (longest guest prefix; last wins on
   a tie), or NULL for the rootfs.  Mirrors fastpath.c:bind_of(). */
static inline const struct px_bind *px_bind_of(const struct px_table *t, const char *g)
{
  const struct px_bind *bd = NULL;
  for (int i = 0; i < t->nbinds; i++) {
    if (!px_under(g, t->binds[i].guest, t->binds[i].glen)) continue;
    if (bd && t->binds[i].glen < bd->glen) continue; /* keep the longer */
    bd = &t->binds[i];                               /* >= : last wins on tie */
  }
  return bd;
}

/* Guest absolute path -> host path.  Returns length, or -1 on overflow.
   A non-absolute path is copied unchanged (the kernel cwd is already host). */
static inline int px_to_host(const struct px_table *t, const char *g, char *out, size_t cap)
{
  if (g[0] != '/') { size_t n = strlen(g); if (n + 1 > cap) return -1; memcpy(out, g, n + 1); return (int)n; }
  const struct px_bind *bd = px_bind_of(t, g);
  int n;
  if (bd) {
    const char *tail = g + bd->glen;            /* "" or "/..." */
    n = snprintf(out, cap, "%s%s", bd->host, tail);
  } else {
    n = snprintf(out, cap, "%s%s", t->root, strcmp(g, "/") ? g : "");
  }
  return (n >= 0 && (size_t)n < cap) ? n : -1;
}

/* Host path -> the guest path that names it, or -1 if none/ambiguous.  Deepest
   host-prefix bind wins; else strip ROOT.  Used to detranslate getcwd/readlink.
   Mirrors fastpath.c:to_guest()/guest_name() (single-name case). */
static inline int px_to_guest(const struct px_table *t, const char *h, char *out, size_t cap)
{
  /* inside rootfs */
  if (px_under(h, t->root, t->rootlen)) {
    const char *tail = h + t->rootlen;
    int n = snprintf(out, cap, "%s", tail[0] ? tail : "/");
    return (n >= 0 && (size_t)n < cap) ? n : -1;
  }
  /* deepest matching bind host */
  const struct px_bind *bd = NULL;
  for (int i = 0; i < t->nbinds; i++) {
    if (!px_under(h, t->binds[i].host, t->binds[i].hlen)) continue;
    if (!bd || t->binds[i].hlen > bd->hlen) bd = &t->binds[i];
    else if (t->binds[i].hlen == bd->hlen
             && strcmp(t->binds[i].guest, bd->guest) != 0) return -1; /* ambiguous */
  }
  if (!bd) return -1;
  int n = snprintf(out, cap, "%s%s", bd->guest, h + bd->hlen);
  return (n >= 0 && (size_t)n < cap) ? n : -1;
}

#endif /* pathxlat_core.h */
