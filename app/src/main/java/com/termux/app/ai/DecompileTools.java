package com.termux.app.ai;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tools the Decompile screen can run. Every script receives the input file as {@code $1} and its own output
 * folder as {@code $2}; paths are passed as arguments, never pasted into the script text.
 */
public enum DecompileTools {

    JADX("jadx", "jadx", "Java source", "APK / DEX / JAR → readable Java and resources", new String[]{"jadx"},
        "IN=\"$1\"; OUT=\"$2\"; SPLIT=\"\"\n"
            + "case \"$(basename \"$1\" | tr 'A-Z' 'a-z')\" in\n"
            + "  *.apks|*.xapk|*.apkm)\n"
            + "    SPLIT=\"$(mktemp -d)\"\n"
            + "    unzip -qo \"$1\" -d \"$SPLIT\" || { echo \"[jadx] cannot open the split archive\"; exit 1; }\n"
            + "    IN=\"$(find \"$SPLIT\" -maxdepth 2 -name base.apk | head -1)\"\n"
            + "    [ -n \"$IN\" ] || IN=\"$(ls -S \"$SPLIT\"/*.apk \"$SPLIT\"/*/*.apk 2>/dev/null | head -1)\"\n"
            + "    [ -n \"$IN\" ] || { echo \"[jadx] no APK found inside the archive\"; rm -rf \"$SPLIT\"; exit 1; }\n"
            + "    echo \"[jadx] split archive: decompiling $(basename \"$IN\") (the base module)\" ;;\n"
            + "esac\n"
            + "mkdir -p \"$OUT\"; LOGF=\"$(mktemp)\"\n"
            + "have_output() { [ -d \"$OUT/sources\" ] && [ -n \"$(find \"$OUT/sources\" -name '*.java' -print -quit 2>/dev/null)\" ]; }\n"
            + "attempt() {\n"
            + "  echo \"[jadx] $1\"; shift\n"
            + "  rm -rf \"$OUT/sources\"\n"
            + "  jadx \"$@\" -d \"$OUT\" \"$IN\" 2>&1 | tee \"$LOGF\"\n"
            + "}\n"
            + "echo \"[jadx] decompiling $(basename \"$IN\")\"\n"
            + "attempt \"pass 1: normal (bad code is shown instead of dropped)\" -j 2 --show-bad-code\n"
            + "have_output || attempt \"pass 2: low memory (one thread, no debug info)\" -j 1 --no-debug-info --show-bad-code\n"
            + "have_output || attempt \"pass 3: fallback mode (raw instructions)\" -j 1 --fallback\n"
            + "if [ -n \"$SPLIT\" ]; then rm -rf \"$SPLIT\"; fi\n"
            + "if ! have_output; then rm -f \"$LOGF\"; echo \"[jadx] no Java was produced. Use the apktool smali for this file.\"; exit 1; fi\n"
            + "ERRS=\"$(grep -cE '(^| )ERROR +- ' \"$LOGF\" 2>/dev/null)\"; rm -f \"$LOGF\"\n"
            + "TOTAL=\"$(find \"$OUT/sources\" -name '*.java' 2>/dev/null | wc -l)\"\n"
            + "SHORT=\"$(find \"$OUT/sources\" -regextype posix-extended -regex '.*/[A-Za-z]{1,2}\\.java' 2>/dev/null | wc -l)\"\n"
            + "echo \"[jadx] $TOTAL classes, ${ERRS:-0} decompile errors (affected methods are marked in the code: read those in the apktool smali)\"\n"
            + "if [ \"$TOTAL\" -gt 50 ] && [ $((SHORT * 100 / TOTAL)) -ge 40 ]; then\n"
            + "  echo \"[jadx] note: $SHORT of $TOTAL classes have 1-2 letter names, so the app is name-obfuscated. Names are not recoverable; follow strings and calls instead.\"\n"
            + "fi\n"
            + "exit 0\n"),

    APKTOOL("apktool", "apktool", "Smali + resources", "APK → smali, manifest, decoded resources (rebuildable)", new String[]{"apktool"},
        "IN=\"$1\"\n"
            + "case \"$(basename \"$1\" | tr 'A-Z' 'a-z')\" in\n"
            + "  *.apks|*.xapk|*.apkm)\n"
            + "    SPLIT=\"$(mktemp -d)\"\n"
            + "    unzip -qo \"$1\" -d \"$SPLIT\" || { echo \"[apktool] cannot open the split archive\"; exit 1; }\n"
            + "    IN=\"$(find \"$SPLIT\" -maxdepth 2 -name base.apk | head -1)\"\n"
            + "    [ -n \"$IN\" ] || IN=\"$(ls -S \"$SPLIT\"/*.apk \"$SPLIT\"/*/*.apk 2>/dev/null | head -1)\"\n"
            + "    [ -n \"$IN\" ] || { echo \"[apktool] no APK found inside the archive\"; rm -rf \"$SPLIT\"; exit 1; }\n"
            + "    echo \"[apktool] split archive: decoding $(basename \"$IN\") (the base module)\" ;;\n"
            + "esac\n"
            + RebuildActivity.APKTOOL_PICK
            + "pick_apktool \"\"\n"
            + "echo \"[apktool] decoding $(basename \"$IN\")\"\n"
            + "\"${APKT[@]}\" d -f --keep-broken-res -o \"$2\" \"$IN\" \\\n"
            + "  || { echo \"[apktool] retrying without --keep-broken-res\"; \"${APKT[@]}\" d -f -o \"$2\" \"$IN\"; } \\\n"
            + "  || { if [ \"${APKT[0]}\" = java ] && command -v apktool >/dev/null 2>&1; then echo \"[apktool] retrying with the installed apktool\"; apktool d -f -o \"$2\" \"$IN\"; else false; fi; } \\\n"
            + "  || { echo \"[apktool] resources could not be decoded: decoding the code only (smali, no res/)\"; \"${APKT[@]}\" d -f -r -o \"$2\" \"$IN\"; }\n"
            + "RC=$?\n"
            + "if [ -n \"${SPLIT:-}\" ]; then rm -rf \"$SPLIT\"; fi\n"
            + "[ \"$RC\" -eq 0 ] || exit \"$RC\"\n"
            + "if command -v python >/dev/null 2>&1; then\n"
            + "python - \"$2\" <<'PYEOF' || echo \"[apktool] resource rename skipped\"\n"
            + "import os, re, sys\n"
            + "root = sys.argv[1]\n"
            + "res = os.path.join(root, 'res')\n"
            + "pub = os.path.join(res, 'values', 'public.xml')\n"
            + "if not os.path.isfile(pub):\n"
            + "    sys.exit(0)\n"
            + "def rd(p):\n"
            + "    with open(p, 'r', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "        return f.read()\n"
            + "txt = rd(pub)\n"
            + "types = {}\n"
            + "for m in re.finditer('<public type=.([a-z_]+). name=.APKTOOL_DUMMY_0x([0-9a-fA-F]{8}).', txt):\n"
            + "    types[m.group(2).lower()] = m.group(1)\n"
            + "if not types:\n"
            + "    sys.exit(0)\n"
            + "used = set(re.findall('name=.([A-Za-z0-9_.]+).', txt))\n"
            + "newname = {}\n"
            + "for h, t in types.items():\n"
            + "    n = t + '_' + h\n"
            + "    while n in used:\n"
            + "        n += '_'\n"
            + "    used.add(n)\n"
            + "    newname[h] = n\n"
            + "pat = re.compile('APKTOOL_DUMMY_0x([0-9a-fA-F]{8})')\n"
            + "def sub(m):\n"
            + "    return newname.get(m.group(1).lower(), m.group(0))\n"
            + "files = 0\n"
            + "for d, _, fs in os.walk(res):\n"
            + "    for fn in fs:\n"
            + "        if not fn.endswith('.xml'):\n"
            + "            continue\n"
            + "        p = os.path.join(d, fn)\n"
            + "        s = rd(p)\n"
            + "        if 'APKTOOL_DUMMY_0x' not in s:\n"
            + "            continue\n"
            + "        with open(p, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "            f.write(pat.sub(sub, s))\n"
            + "        files += 1\n"
            + "print('[apktool] renamed %d unnamed resources in %d files (APKTOOL_DUMMY_0x7f... -> type_7f...)' % (len(newname), files))\n"
            + "PYEOF\n"
            + "fi\n"),

    GHIDRA("ghidra", "Ghidra", "Decompiled C", "Native binary → C pseudo-code for every function (slow, large)", new String[]{"re-decompile"},
        "mkdir -p \"$2\"\n"
            + "echo \"[ghidra] analysing $(basename \"$1\") - the first run can take several minutes\"\n"
            + "re-decompile \"$1\" --list \"$2/functions.txt\" || exit 1\n"
            + "re-decompile \"$1\" --all \"$2/decompiled.c\"\n"),

    RADARE2("radare2", "radare2", "Info + functions", "Headers, imports, exports, sections, strings and a function list", new String[]{"rabin2", "r2"},
        "mkdir -p \"$2\"\n"
            + "echo \"[radare2] headers\";  rabin2 -I \"$1\"  > \"$2/info.txt\" 2>&1\n"
            + "echo \"[radare2] imports\";  rabin2 -i \"$1\"  > \"$2/imports.txt\" 2>&1\n"
            + "echo \"[radare2] exports\";  rabin2 -E \"$1\"  > \"$2/exports.txt\" 2>&1\n"
            + "echo \"[radare2] sections\"; rabin2 -S \"$1\"  > \"$2/sections.txt\" 2>&1\n"
            + "echo \"[radare2] strings\";  rabin2 -zz \"$1\" > \"$2/strings.txt\" 2>&1\n"
            + "echo \"[radare2] analysing functions\"\n"
            + "r2 -q -e scr.color=0 -c 'aa; afl' \"$1\" > \"$2/functions.txt\" 2>&1\n"),

    NATIVE("native", "Native libs", "Info per .so", "Imports, exports and strings of each native library inside an APK (best ABI for this phone)", new String[]{"rabin2"},
        "mkdir -p \"$2\"\n"
            + "ABI=\"\"\n"
            + "for a in arm64-v8a armeabi-v7a x86_64 x86; do\n"
            + "  if unzip -l \"$1\" \"lib/$a/*.so\" 2>/dev/null | grep -q '\\.so'; then ABI=\"$a\"; break; fi\n"
            + "done\n"
            + "[ -n \"$ABI\" ] || { echo \"[native] no native libraries in this file\"; exit 0; }\n"
            + "echo \"[native] using lib/$ABI\"\n"
            + "unzip -qo \"$1\" \"lib/$ABI/*.so\" -d \"$2/libs\" || { echo \"[native] cannot extract the libraries\"; exit 1; }\n"
            + "n=0\n"
            + "for so in \"$2\"/libs/lib/\"$ABI\"/*.so; do\n"
            + "  [ -f \"$so\" ] || continue\n"
            + "  n=$((n+1))\n"
            + "  if [ \"$n\" -gt 40 ]; then echo \"[native] more than 40 libraries: stopped after 40\"; break; fi\n"
            + "  b=\"$(basename \"$so\" .so)\"\n"
            + "  echo \"[native] $b\"\n"
            + "  { rabin2 -I \"$so\"; echo '--- imports'; rabin2 -i \"$so\"; echo '--- exports'; rabin2 -E \"$so\"; echo '--- strings'; rabin2 -zz \"$so\" | head -n 3000; } > \"$2/$b.txt\" 2>&1\n"
            + "done\n"
            + "echo \"[native] analysed $((n>40?40:n)) libraries; the .so files are in native/libs/lib/$ABI for Ghidra or radare2\"\n"),

    TRIAGE("triage", "Quick triage", "One-page summary", "File type, hash, imports and interesting strings in seconds", new String[]{"re-triage"},
        "mkdir -p \"$2\"\n"
            + "re-triage \"$1\" > \"$2/triage.txt\" 2>&1\n"
            + "head -n 25 \"$2/triage.txt\"\n");

    @NonNull public final String subdir;
    @NonNull public final String label;
    @NonNull public final String produces;
    @NonNull public final String description;
    /** Executables (in $PREFIX/bin) that must exist for this tool to work. */
    @NonNull public final String[] requires;
    @NonNull public final String script;

    DecompileTools(@NonNull String subdir, @NonNull String label, @NonNull String produces, @NonNull String description,
                   @NonNull String[] requires, @NonNull String script) {
        this.subdir = subdir;
        this.label = label;
        this.produces = produces;
        this.description = description;
        this.requires = requires;
        this.script = script;
    }

    /** Which tools are worth ticking by default for this file, judged by its extension. */
    @NonNull
    public static Set<DecompileTools> suggest(@NonNull File input) {
        String n = input.getName().toLowerCase(Locale.US);
        Set<DecompileTools> s = EnumSet.noneOf(DecompileTools.class);
        if (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")) {
            s.add(JADX);
            s.add(APKTOOL);
            // Native libraries hold the real logic of many apps (and all of it for Flutter/Unity): list them too.
            if (ApkScan.scan(input).nativeLibs > 0) s.add(NATIVE);
        } else if (n.endsWith(".dex") || n.endsWith(".jar") || n.endsWith(".aar") || n.endsWith(".war") || n.endsWith(".class")) {
            s.add(JADX);
        } else {
            // Native code (.so, ELF, PE, firmware, no extension…): a fast overview first, Ghidra for the deep dive.
            s.add(TRIAGE);
            s.add(RADARE2);
        }
        return s;
    }

    /** Tools in the order they run. */
    @NonNull
    public static List<DecompileTools> ordered(@NonNull Set<DecompileTools> selected) {
        List<DecompileTools> out = new ArrayList<>();
        for (DecompileTools t : values()) if (selected.contains(t)) out.add(t);
        return out;
    }

    /** Base name used for the output folder and the zip: the file name without its last extension, made safe. */
    @NonNull
    public static String baseName(@NonNull File input) {
        String n = input.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        return n.length() > 80 ? n.substring(0, 80) : n;
    }
}
