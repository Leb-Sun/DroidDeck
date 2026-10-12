/*
 * Unit test for pathxlat_core.h: checks the longest-prefix / identity /
 * nested-override bind semantics against hand-computed expectations modelled
 * on a DroidDeck session (01 S1.2, SecondaryLibrary.kt), and round-trips
 * to_host -> to_guest.  Spike code.  Build: see spike.sh.
 */
#include "../pathxlat/pathxlat_core.h"
#include <stdio.h>
#include <string.h>

static int fails;
static struct px_table T;

static void eq(const char *what, const char *got, const char *want)
{
  if (strcmp(got, want) != 0) {
    printf("FAIL %-28s got '%s' want '%s'\n", what, got, want);
    fails++;
  } else {
    printf("ok   %-28s -> %s\n", what, got);
  }
}

static const char *H(const char *g)
{
  static char out[PX_PATH_MAX];
  if (px_to_host(&T, g, out, sizeof out) < 0) return "<ERR>";
  return out;
}
static const char *G(const char *h)
{
  static char out[PX_PATH_MAX];
  if (px_to_guest(&T, h, out, sizeof out) < 0) return "<AMBIG/NONE>";
  return out;
}

int main(void)
{
  /* ROOT at an odd path; identity binds for files/cache/shared storage; a
     remapped bind (/dev/shm -> cache/shm); a parent SD-library bind with a
     nested private override (compatdata), exactly the pattern in
     SecondaryLibrary.kt ("parent binds come first; the more specific private
     directories override them"); and a bind over a single file. */
  const char *root = "/data/odd/app.x/files/linuxfs";
  const char *binds =
    "/storage/emulated/0:/storage/emulated/0"                       /* identity */
    "|/data/odd/app.x/files:/data/odd/app.x/files"                  /* identity */
    "|/data/odd/app.x/cache/shm:/dev/shm"                           /* remap */
    "|/storage/emulated/0/SteamLibrary:/mnt/droiddeck-sd"           /* parent */
    "|/data/odd/app.x/files/sl/compatdata:/mnt/droiddeck-sd/steamapps/compatdata" /* nested override */
    "|/data/odd/app.x/files/machine-id:/etc/machine-id";           /* single file */

  if (px_parse(&T, root, binds) != 0) { printf("parse failed\n"); return 1; }
  printf("nbinds=%d root=%s\n", T.nbinds, T.root);

  /* plain rootfs paths */
  eq("/usr/lib", H("/usr/lib"), "/data/odd/app.x/files/linuxfs/usr/lib");
  eq("/etc/passwd", H("/etc/passwd"), "/data/odd/app.x/files/linuxfs/etc/passwd");
  eq("/", H("/"), "/data/odd/app.x/files/linuxfs");
  eq("/bin/sh", H("/bin/sh"), "/data/odd/app.x/files/linuxfs/bin/sh");

  /* identity binds: path unchanged */
  eq("identity shared", H("/storage/emulated/0/game.iso"), "/storage/emulated/0/game.iso");

  /* remapped bind */
  eq("remap /dev/shm", H("/dev/shm/wine"), "/data/odd/app.x/cache/shm/wine");
  eq("remap /dev/shm root", H("/dev/shm"), "/data/odd/app.x/cache/shm");

  /* parent bind */
  eq("parent sd", H("/mnt/droiddeck-sd/steamapps/x"),
     "/storage/emulated/0/SteamLibrary/steamapps/x");

  /* nested override wins over the parent (longest prefix) */
  eq("nested override", H("/mnt/droiddeck-sd/steamapps/compatdata/570"),
     "/data/odd/app.x/files/sl/compatdata/570");
  /* a sibling under the parent still uses the parent */
  eq("sibling under parent", H("/mnt/droiddeck-sd/steamapps/common/x"),
     "/storage/emulated/0/SteamLibrary/steamapps/common/x");

  /* single-file bind */
  eq("single file", H("/etc/machine-id"), "/data/odd/app.x/files/machine-id");
  /* a near-miss must NOT match the single-file bind (component boundary) */
  eq("near miss", H("/etc/machine-id-2"),
     "/data/odd/app.x/files/linuxfs/etc/machine-id-2");

  /* detranslation (getcwd/readlink results) */
  eq("deH rootfs", G("/data/odd/app.x/files/linuxfs/usr/lib"), "/usr/lib");
  eq("deH identity", G("/storage/emulated/0/x"), "/storage/emulated/0/x");
  eq("deH remap", G("/data/odd/app.x/cache/shm/y"), "/dev/shm/y");
  eq("deH nested", G("/data/odd/app.x/files/sl/compatdata/570"),
     "/mnt/droiddeck-sd/steamapps/compatdata/570");

  /* round trip for a handful of guest paths */
  const char *samples[] = { "/usr/lib/x", "/dev/shm/z", "/mnt/droiddeck-sd/steamapps/common/a",
                            "/mnt/droiddeck-sd/steamapps/compatdata/42", NULL };
  for (int i = 0; samples[i]; i++) {
    char h[PX_PATH_MAX], g2[PX_PATH_MAX];
    px_to_host(&T, samples[i], h, sizeof h);
    int r = px_to_guest(&T, h, g2, sizeof g2);
    char what[64]; snprintf(what, sizeof what, "roundtrip %s", samples[i]);
    eq(what, (r >= 0 ? g2 : "<ERR>"), samples[i]);
  }

  printf(fails ? "\n%d FAILURE(S)\n" : "\nALL PASS\n", fails);
  return fails ? 1 : 0;
}
