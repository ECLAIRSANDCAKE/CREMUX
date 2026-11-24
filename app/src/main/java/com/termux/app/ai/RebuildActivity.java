package com.termux.app.ai;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.termux.R;
import com.termux.app.ui.GitHubSyncActivity;
import com.termux.app.ui.Picker;
import com.termux.app.ui.RedesignActivity;
import com.termux.app.ui.UiColors;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turn a modified project back into an installable APK.
 *
 * <ul>
 *   <li><b>Decompiled APK</b> (an apktool folder): rebuilt, zip-aligned and signed right here on the phone.</li>
 *   <li><b>Source code project</b> (Gradle): built either on GitHub (Actions) or on a PC.</li>
 * </ul>
 */
public class RebuildActivity extends RedesignActivity {

    private static final int MAX_LOG_CHARS = 20000;

    private static final String SIGNER_SHA256 = "e1299fd6fcf4da527dd53735b56127e8ea922a321128123b9c32d619bba1d835";

    /** Shell helpers shared by decompile and rebuild: pin a stable apktool and keep one version per project. */
    static final String APKTOOL_PICK =
        ""
            + "PJ=\"$HOME/.re/tools/apktool-2.10.0.jar\"; APKT=(apktool); AAPT=()\n"
            + "pin_apktool() {\n"
            + "  [ -s \"$PJ\" ] && return 0\n"
            + "  command -v java >/dev/null 2>&1 && command -v curl >/dev/null 2>&1 || return 1\n"
            + "  mkdir -p \"$HOME/.re/tools\"\n"
            + "  curl -fsL --retry 2 -o \"$PJ.part\" \"https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar\" || { rm -f \"$PJ.part\"; return 1; }\n"
            + "  [ \"$(sha256sum \"$PJ.part\" | cut -d' ' -f1)\" = \"c0350abbab5314248dfe2ee0c907def4edd14f6faef1f5d372d3d4abd28f0431\" ] || { rm -f \"$PJ.part\"; return 1; }\n"
            + "  mv \"$PJ.part\" \"$PJ\"\n"
            + "}\n"
            + "# $1 = version in apktool.yml (rebuild) or empty (decompile). Rule: rebuild with the version that decoded the project.\n"
            + "pick_apktool() {\n"
            + "  local iv; iv=\"$(apktool --version 2>/dev/null | head -1)\"\n"
            + "  if [ -n \"$1\" ]; then\n"
            + "    case \"$iv\" in \"${1%%-*}\"*) return 0 ;; esac\n"
            + "    case \"$1\" in\n"
            + "      2.*) pin_apktool && APKT=(java -jar \"$PJ\") && echo \"[apktool] project was decoded with $1: using the pinned 2.10.0\" ;;\n"
            + "      *) echo \"[doctor] note: project was decoded with apktool $1 but $iv is installed\" ;;\n"
            + "    esac\n"
            + "  else\n"
            + "    pin_apktool && APKT=(java -jar \"$PJ\") && echo \"[apktool] using the pinned stable apktool 2.10.0\"\n"
            + "  fi\n"
            + "  if [ \"${APKT[0]}\" = java ] && command -v aapt2 >/dev/null 2>&1; then AAPT=(-a \"$(command -v aapt2)\"); fi\n"
            + "  return 0\n"
            + "}\n";

    /** $1 = apktool folder, $2 = output folder. Prints "OK <path>" on the last line when it worked. */
    static final String APK_SCRIPT =
        "set -u\n"
            + "SRC=\"$1\"; OUT=\"$2\"\n"
            + "CRASH=\"${3:-0}\"\n"
            + "if [ ! -f \"$SRC/apktool.yml\" ]; then echo \"! No apktool.yml in this folder. Choose a folder made by apktool.\"; exit 2; fi\n"
            + "command -v apktool >/dev/null 2>&1 || { echo \"! apktool is missing. Use Install RE tools first.\"; exit 2; }\n"
            + "command -v java >/dev/null 2>&1 || { echo \"! Java is missing. Run: pkg install openjdk-17\"; exit 2; }\n"
            + "TOOLS=\"$HOME/.re/tools\"; JAR=\"$TOOLS/uber-apk-signer.jar\"\n"
            + "mkdir -p \"$TOOLS\" \"$OUT\"\n"
            + "trap 'rm -rf \"$OUT/.work\" \"$OUT/.sanitize.py\" \"$OUT/.inject.py\" \"$OUT/.CrashLog.smali\"' EXIT\n"
            + "if [ ! -s \"$JAR\" ]; then\n"
            + "  echo \"[signer] downloading uber-apk-signer (about 2 MB, one time)\"\n"
            + "  curl -fL --retry 2 -o \"$JAR.part\" "
            + "\"https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar\" \\\n"
            + "    || { rm -f \"$JAR.part\"; echo \"! Could not download the signer (check your connection).\"; exit 3; }\n"
            + "  # Pinned SHA-256: refuse a tampered or truncated download, because this jar signs your APKs.\n"
            + "  if [ \"$(sha256sum \"$JAR.part\" | cut -d' ' -f1)\" != \"" + SIGNER_SHA256 + "\" ]; then\n"
            + "    rm -f \"$JAR.part\"; echo \"! The signer download failed its checksum and was discarded.\"; exit 3\n"
            + "  fi\n"
            + "  mv \"$JAR.part\" \"$JAR\"\n"
            + "fi\n"
            + "rm -rf \"$OUT\"/*.apk \"$OUT/signed\"\n"
            + APKTOOL_PICK
            + "echo \"[doctor] checking tools\"\n"
            + "command -v aapt2 >/dev/null 2>&1 || echo \"[doctor] ! aapt2 not found. Run: pkg install aapt2\"\n"
            + "command -v zipalign >/dev/null 2>&1 || echo \"[doctor] note: zipalign not found, the built-in aligner is used\"\n"
            + "command -v python >/dev/null 2>&1 || echo \"[doctor] note: python not found, invalid resource names cannot be fixed automatically\"\n"
            + "pick_apktool \"$(sed -n 's/^version: *//p' \"$SRC/apktool.yml\" | head -1)\"\n"
            + "BSRC=\"$SRC\"; SAN=\"$OUT/.sanitize.py\"\n"
            + "cat > \"$SAN\" <<'PYEOF'\n"
            + "import os, re, sys, shutil\n"
            + "src = sys.argv[1]\n"
            + "dst = sys.argv[2] if len(sys.argv) > 2 else ''\n"
            + "OK = set('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-')\n"
            + "Q = chr(34)\n"
            + "def bad(n):\n"
            + "    return any(ord(c) < 128 and c not in OK for c in n)\n"
            + "def entry(fn):\n"
            + "    return fn.split('.')[0]\n"
            + "res = os.path.join(src, 'res')\n"
            + "names = set()\n"
            + "todo = []\n"
            + "pub = os.path.join(res, 'values', 'public.xml')\n"
            + "pat = re.compile('<public [^>]*name=' + Q + '([^' + Q + ']+)' + Q)\n"
            + "if os.path.isfile(pub):\n"
            + "    with open(pub, encoding='utf-8', errors='replace') as f:\n"
            + "        for m in pat.finditer(f.read()):\n"
            + "            names.add(m.group(1))\n"
            + "            if bad(m.group(1)) and m.group(1) not in todo:\n"
            + "                todo.append(m.group(1))\n"
            + "if os.path.isdir(res):\n"
            + "    for d in sorted(os.listdir(res)):\n"
            + "        p = os.path.join(res, d)\n"
            + "        if not os.path.isdir(p) or d.startswith('values'):\n"
            + "            continue\n"
            + "        for fn in os.listdir(p):\n"
            + "            e = entry(fn)\n"
            + "            names.add(e)\n"
            + "            if e and bad(e) and e not in todo:\n"
            + "                todo.append(e)\n"
            + "def repair(root, apply):\n"
            + "    rs = os.path.join(root, 'res')\n"
            + "    ap = os.path.join(rs, 'values', 'attrs.xml')\n"
            + "    if not os.path.isfile(ap):\n"
            + "        return 0\n"
            + "    with open(ap, encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "        atxt = f.read()\n"
            + "    arx = re.compile('<attr name=' + Q + '([A-Za-z0-9_.]+)' + Q + '([^>]*?)(/>|>(.*?)</attr>)', re.S)\n"
            + "    attrs = {}\n"
            + "    for m in arx.finditer(atxt):\n"
            + "        body = m.group(4) or ''\n"
            + "        fm = re.search('format=' + Q + '([^' + Q + ']*)' + Q, m.group(2))\n"
            + "        kind = 'enum' if '<enum' in body else ('flag' if '<flag' in body else 'fmt')\n"
            + "        vals = {}\n"
            + "        for e in re.finditer('<(?:enum|flag) name=' + Q + '([^' + Q + ']*)' + Q + ' value=' + Q + '([^' + Q + ']*)' + Q, body):\n"
            + "            try:\n"
            + "                vals[int(e.group(2), 0) & 0xFFFFFFFF] = e.group(1)\n"
            + "            except ValueError:\n"
            + "                pass\n"
            + "        attrs[m.group(1)] = (kind, fm.group(1) if fm else '', vals)\n"
            + "    adds = {}\n"
            + "    fmts = {}\n"
            + "    count = [0]\n"
            + "    irx = re.compile('([ \\t]*)<item name=' + Q + '([A-Za-z0-9_.]+)' + Q + '>([^<]*)</item>([ \\t]*\\r?\\n?)')\n"
            + "    def enum_name(name, v):\n"
            + "        kind, fmt, vals = attrs[name]\n"
            + "        n = int(v, 0) & 0xFFFFFFFF\n"
            + "        if n not in vals:\n"
            + "            vals[n] = 'cremux_' + v.replace('-', 'm').replace('x', '')\n"
            + "            adds.setdefault(name, []).append((kind, vals[n], n))\n"
            + "        return vals[n]\n"
            + "    def fixattr(m):\n"
            + "        name, v = m.group(3), m.group(4)\n"
            + "        if m.group(2) == 'android' or name not in attrs or attrs[name][0] not in ('enum', 'flag'):\n"
            + "            return m.group(0)\n"
            + "        count[0] += 1\n"
            + "        return m.group(1) + m.group(2) + ':' + name + '=' + Q + enum_name(name, v) + Q\n"
            + "    arx2 = re.compile('([ \\\\t\\\\r\\\\n])([A-Za-z0-9_]+):([A-Za-z0-9_.]+)=' + Q + '(-?[0-9]+|0x[0-9a-fA-F]+)' + Q)\n"
            + "    def fix(m):\n"
            + "        name, v = m.group(2), m.group(3).strip()\n"
            + "        if name not in attrs:\n"
            + "            return m.group(0)\n"
            + "        kind, fmt, vals = attrs[name]\n"
            + "        if re.match('^APKTOOL_MISSING_0x[0-9a-fA-F]+$', v):\n"
            + "            count[0] += 1\n"
            + "            return ''\n"
            + "        if not re.match('^(-?[0-9]+|0x[0-9a-fA-F]+)$', v):\n"
            + "            return m.group(0)\n"
            + "        n = int(v, 0) & 0xFFFFFFFF\n"
            + "        if kind in ('enum', 'flag'):\n"
            + "            nm = enum_name(name, v)\n"
            + "            count[0] += 1\n"
            + "            return m.group(1) + '<item name=' + Q + name + Q + '>' + nm + '</item>' + m.group(4)\n"
            + "        parts = fmt.split('|')\n"
            + "        if fmt and not ({'integer', 'float', 'string'} & set(parts)):\n"
            + "            fmts[name] = fmt + '|integer'\n"
            + "            attrs[name] = (kind, fmts[name], vals)\n"
            + "            count[0] += 1\n"
            + "        return m.group(0)\n"
            + "    for d in sorted(os.listdir(rs)):\n"
            + "        p = os.path.join(rs, d)\n"
            + "        if not d.startswith('values') or not os.path.isdir(p):\n"
            + "            continue\n"
            + "        for fn in sorted(os.listdir(p)):\n"
            + "            if not fn.endswith('.xml') or fn in ('attrs.xml', 'public.xml', 'ids.xml'):\n"
            + "                continue\n"
            + "            fp = os.path.join(p, fn)\n"
            + "            with open(fp, encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "                t = f.read()\n"
            + "            if '<item name=' not in t:\n"
            + "                continue\n"
            + "            t2 = irx.sub(fix, t)\n"
            + "            if apply and t2 != t:\n"
            + "                with open(fp, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "                    f.write(t2)\n"
            + "    for d in sorted(os.listdir(rs)):\n"
            + "        p = os.path.join(rs, d)\n"
            + "        if d.startswith('values') or not os.path.isdir(p):\n"
            + "            continue\n"
            + "        for fn in sorted(os.listdir(p)):\n"
            + "            if not fn.endswith('.xml'):\n"
            + "                continue\n"
            + "            fp = os.path.join(p, fn)\n"
            + "            with open(fp, encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "                t = f.read()\n"
            + "            t2 = arx2.sub(fixattr, t)\n"
            + "            if apply and t2 != t:\n"
            + "                with open(fp, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "                    f.write(t2)\n"
            + "    if apply and (adds or fmts):\n"
            + "        def ra(m):\n"
            + "            name = m.group(1)\n"
            + "            head, tail, body = m.group(2), m.group(3), m.group(4)\n"
            + "            if name in fmts:\n"
            + "                head = re.sub('format=' + Q + '[^' + Q + ']*' + Q, 'format=' + Q + fmts[name] + Q, head)\n"
            + "            if name in adds and tail != '/>':\n"
            + "                ins = ''.join('    <%s name=%s%s%s value=%s%s%s />\\n    ' % (k, Q, nm, Q, Q, ('0x%08x' % n) if k == 'flag' else str(n if n < 0x80000000 else n - 0x100000000), Q) for k, nm, n in adds[name])\n"
            + "                return '<attr name=' + Q + name + Q + head + '>' + body.rstrip(' ') + ins.rstrip(' ') + '</attr>'\n"
            + "            return '<attr name=' + Q + name + Q + head + tail\n"
            + "        atxt2 = arx.sub(ra, atxt)\n"
            + "        with open(ap, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "            f.write(atxt2)\n"
            + "    return count[0]\n"
            + "if not dst:\n"
            + "    import xml.etree.ElementTree as ET\n"
            + "    bad_xml = []\n"
            + "    for r, _, fs in os.walk(res):\n"
            + "        for f in fs:\n"
            + "            if f.endswith('.xml'):\n"
            + "                try:\n"
            + "                    ET.parse(os.path.join(r, f))\n"
            + "                except Exception as e:\n"
            + "                    bad_xml.append(os.path.relpath(os.path.join(r, f), src) + ': ' + str(e))\n"
            + "    for b in bad_xml[:10]:\n"
            + "        print('[doctor] ! malformed XML (aapt2 will fail): ' + b)\n"
            + "if not dst:\n"
            + "    sys.exit(10 if (todo or repair(src, False)) else 0)\n"
            + "mp = {}\n"
            + "for old in todo:\n"
            + "    new = ''.join(c if (ord(c) >= 128 or c in OK) else '_' for c in old)\n"
            + "    while new in names:\n"
            + "        new += '_'\n"
            + "    names.add(new)\n"
            + "    mp[old] = new\n"
            + "shutil.copytree(src, dst, symlinks=True, ignore=shutil.ignore_patterns('build', 'dist'))\n"
            + "res = os.path.join(dst, 'res')\n"
            + "files = 0\n"
            + "for d in os.listdir(res):\n"
            + "    p = os.path.join(res, d)\n"
            + "    if not os.path.isdir(p) or d.startswith('values'):\n"
            + "        continue\n"
            + "    for fn in os.listdir(p):\n"
            + "        e = entry(fn)\n"
            + "        if e in mp:\n"
            + "            os.rename(os.path.join(p, fn), os.path.join(p, mp[e] + fn[len(e):]))\n"
            + "            files += 1\n"
            + "refs = [(re.compile('(?<=/)' + re.escape(o) + '(?![A-Za-z0-9_.$-])'), n) for o, n in mp.items()]\n"
            + "nm = [('name=' + Q + o + Q, 'name=' + Q + n + Q) for o, n in mp.items()]\n"
            + "targets = [os.path.join(dst, 'AndroidManifest.xml')]\n"
            + "for r, _, fs in os.walk(res):\n"
            + "    targets += [os.path.join(r, f) for f in fs if f.endswith('.xml')]\n"
            + "for t in targets:\n"
            + "    if not os.path.isfile(t):\n"
            + "        continue\n"
            + "    with open(t, encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "        s = f.read()\n"
            + "    o = s\n"
            + "    for rx, n in refs:\n"
            + "        s = rx.sub(n, s)\n"
            + "    for a, b in nm:\n"
            + "        s = s.replace(a, b)\n"
            + "    if s != o:\n"
            + "        with open(t, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "            f.write(s)\n"
            + "fx = repair(dst, True)\n"
            + "print('[sanitize] in a temporary copy (your project is untouched): renamed %d invalid resource names, repaired %d style values' % (len(mp), fx))\n"
            + "PYEOF\n"
            + "# aapt2 rejects resource names with characters like '$' (R8-generated $avd_*__0 drawables): build a fixed temporary copy.\n"
            + "if command -v python >/dev/null 2>&1; then\n"
            + "  python \"$SAN\" \"$SRC\"; rc=$?\n"
            + "  if [ \"$rc\" = 10 ] || [ \"$CRASH\" = 1 ]; then\n"
            + "    rm -rf \"$OUT/.work\"\n"
            + "    if python \"$SAN\" \"$SRC\" \"$OUT/.work\"; then BSRC=\"$OUT/.work\"; else echo \"! name sanitizer failed, building the original folder\"; rm -rf \"$OUT/.work\"; fi\n"
            + "  fi\n"
            + "fi\n"
            + "if [ \"$CRASH\" = 1 ]; then\n"
            + "  if [ \"$BSRC\" = \"$SRC\" ]; then echo \"[crashlog] skipped (needs python)\"; else\n"
            + "cat > \"$OUT/.CrashLog.smali\" <<'SMEOF'\n"
            + ".class public Lcremux/CrashLog;\n"
            + ".super Ljava/lang/Object;\n"
            + ".implements Ljava/lang/Thread$UncaughtExceptionHandler;\n"
            + "\n"
            + ".field private static done:Z\n"
            + "\n"
            + ".field private final ctx:Landroid/content/Context;\n"
            + "\n"
            + ".field private final prev:Ljava/lang/Thread$UncaughtExceptionHandler;\n"
            + "\n"
            + ".method private constructor <init>(Landroid/content/Context;Ljava/lang/Thread$UncaughtExceptionHandler;)V\n"
            + "    .locals 0\n"
            + "\n"
            + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
            + "\n"
            + "    iput-object p1, p0, Lcremux/CrashLog;->ctx:Landroid/content/Context;\n"
            + "\n"
            + "    iput-object p2, p0, Lcremux/CrashLog;->prev:Ljava/lang/Thread$UncaughtExceptionHandler;\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public static install(Landroid/content/Context;)V\n"
            + "    .locals 2\n"
            + "\n"
            + "    sget-boolean v0, Lcremux/CrashLog;->done:Z\n"
            + "\n"
            + "    if-nez v0, :cond_0\n"
            + "\n"
            + "    const/4 v0, 0x1\n"
            + "\n"
            + "    sput-boolean v0, Lcremux/CrashLog;->done:Z\n"
            + "\n"
            + "    invoke-static {}, Ljava/lang/Thread;->getDefaultUncaughtExceptionHandler()Ljava/lang/Thread$UncaughtExceptionHandler;\n"
            + "\n"
            + "    move-result-object v0\n"
            + "\n"
            + "    new-instance v1, Lcremux/CrashLog;\n"
            + "\n"
            + "    invoke-direct {v1, p0, v0}, Lcremux/CrashLog;-><init>(Landroid/content/Context;Ljava/lang/Thread$UncaughtExceptionHandler;)V\n"
            + "\n"
            + "    invoke-static {v1}, Ljava/lang/Thread;->setDefaultUncaughtExceptionHandler(Ljava/lang/Thread$UncaughtExceptionHandler;)V\n"
            + "\n"
            + "    :cond_0\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method private own(Ljava/lang/String;)V\n"
            + "    .locals 4\n"
            + "\n"
            + "    :try_start_0\n"
            + "    iget-object v0, p0, Lcremux/CrashLog;->ctx:Landroid/content/Context;\n"
            + "\n"
            + "    const/4 v1, 0x0\n"
            + "\n"
            + "    invoke-virtual {v0, v1}, Landroid/content/Context;->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;\n"
            + "\n"
            + "    move-result-object v1\n"
            + "\n"
            + "    if-nez v1, :cond_0\n"
            + "\n"
            + "    invoke-virtual {v0}, Landroid/content/Context;->getFilesDir()Ljava/io/File;\n"
            + "\n"
            + "    move-result-object v1\n"
            + "\n"
            + "    :cond_0\n"
            + "    new-instance v2, Ljava/io/File;\n"
            + "\n"
            + "    const-string v3, \"last_crash.txt\"\n"
            + "\n"
            + "    invoke-direct {v2, v1, v3}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V\n"
            + "\n"
            + "    new-instance v1, Ljava/io/FileOutputStream;\n"
            + "\n"
            + "    invoke-direct {v1, v2}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V\n"
            + "\n"
            + "    invoke-virtual {p1}, Ljava/lang/String;->getBytes()[B\n"
            + "\n"
            + "    move-result-object v2\n"
            + "\n"
            + "    invoke-virtual {v1, v2}, Ljava/io/FileOutputStream;->write([B)V\n"
            + "\n"
            + "    invoke-virtual {v1}, Ljava/io/FileOutputStream;->close()V\n"
            + "\n"
            + "    :try_end_0\n"
            + "    .catchall {:try_start_0 .. :try_end_0} :catchall_0\n"
            + "\n"
            + "    :catchall_0\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method private pub(Ljava/lang/String;Ljava/lang/String;)V\n"
            + "    .locals 4\n"
            + "\n"
            + "    :try_start_0\n"
            + "    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n"
            + "\n"
            + "    const/16 v1, 0x1d\n"
            + "\n"
            + "    if-lt v0, v1, :cond_0\n"
            + "\n"
            + "    iget-object v0, p0, Lcremux/CrashLog;->ctx:Landroid/content/Context;\n"
            + "\n"
            + "    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;\n"
            + "\n"
            + "    move-result-object v0\n"
            + "\n"
            + "    new-instance v1, Landroid/content/ContentValues;\n"
            + "\n"
            + "    invoke-direct {v1}, Landroid/content/ContentValues;-><init>()V\n"
            + "\n"
            + "    const-string v2, \"_display_name\"\n"
            + "\n"
            + "    invoke-virtual {v1, v2, p2}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V\n"
            + "\n"
            + "    const-string v2, \"mime_type\"\n"
            + "\n"
            + "    const-string v3, \"text/plain\"\n"
            + "\n"
            + "    invoke-virtual {v1, v2, v3}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V\n"
            + "\n"
            + "    const-string v2, \"relative_path\"\n"
            + "\n"
            + "    const-string v3, \"Download/Cremux\"\n"
            + "\n"
            + "    invoke-virtual {v1, v2, v3}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V\n"
            + "\n"
            + "    const-string v2, \"content://media/external/downloads\"\n"
            + "\n"
            + "    invoke-static {v2}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;\n"
            + "\n"
            + "    move-result-object v2\n"
            + "\n"
            + "    invoke-virtual {v0, v2, v1}, Landroid/content/ContentResolver;->insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;\n"
            + "\n"
            + "    move-result-object v1\n"
            + "\n"
            + "    if-eqz v1, :cond_1\n"
            + "\n"
            + "    invoke-virtual {v0, v1}, Landroid/content/ContentResolver;->openOutputStream(Landroid/net/Uri;)Ljava/io/OutputStream;\n"
            + "\n"
            + "    move-result-object v0\n"
            + "\n"
            + "    invoke-virtual {p1}, Ljava/lang/String;->getBytes()[B\n"
            + "\n"
            + "    move-result-object v1\n"
            + "\n"
            + "    invoke-virtual {v0, v1}, Ljava/io/OutputStream;->write([B)V\n"
            + "\n"
            + "    invoke-virtual {v0}, Ljava/io/OutputStream;->close()V\n"
            + "\n"
            + "    goto :cond_1\n"
            + "\n"
            + "    :cond_0\n"
            + "    const-string v0, \"Download\"\n"
            + "\n"
            + "    invoke-static {v0}, Landroid/os/Environment;->getExternalStoragePublicDirectory(Ljava/lang/String;)Ljava/io/File;\n"
            + "\n"
            + "    move-result-object v0\n"
            + "\n"
            + "    new-instance v1, Ljava/io/File;\n"
            + "\n"
            + "    const-string v2, \"Cremux\"\n"
            + "\n"
            + "    invoke-direct {v1, v0, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V\n"
            + "\n"
            + "    invoke-virtual {v1}, Ljava/io/File;->mkdirs()Z\n"
            + "\n"
            + "    new-instance v0, Ljava/io/File;\n"
            + "\n"
            + "    invoke-direct {v0, v1, p2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V\n"
            + "\n"
            + "    new-instance v1, Ljava/io/FileOutputStream;\n"
            + "\n"
            + "    invoke-direct {v1, v0}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V\n"
            + "\n"
            + "    invoke-virtual {p1}, Ljava/lang/String;->getBytes()[B\n"
            + "\n"
            + "    move-result-object v0\n"
            + "\n"
            + "    invoke-virtual {v1, v0}, Ljava/io/FileOutputStream;->write([B)V\n"
            + "\n"
            + "    invoke-virtual {v1}, Ljava/io/FileOutputStream;->close()V\n"
            + "\n"
            + "    :cond_1\n"
            + "    :try_end_0\n"
            + "    .catchall {:try_start_0 .. :try_end_0} :catchall_0\n"
            + "\n"
            + "    :catchall_0\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method private dump(Ljava/lang/Thread;Ljava/lang/Throwable;)V\n"
            + "    .locals 5\n"
            + "\n"
            + "    iget-object v0, p0, Lcremux/CrashLog;->ctx:Landroid/content/Context;\n"
            + "    invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;\n"
            + "    move-result-object v1\n"
            + "    new-instance v2, Ljava/lang/StringBuilder;\n"
            + "    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V\n"
            + "    const-string v3, \"Package: \"\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    const-string v3, \"\\nTime: \"\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    new-instance v3, Ljava/util/Date;\n"
            + "    invoke-direct {v3}, Ljava/util/Date;-><init>()V\n"
            + "    invoke-virtual {v3}, Ljava/util/Date;->toString()Ljava/lang/String;\n"
            + "    move-result-object v3\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    const-string v3, \"\\nThread: \"\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    invoke-virtual {p1}, Ljava/lang/Thread;->getName()Ljava/lang/String;\n"
            + "    move-result-object v3\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    const-string v3, \"\\nDevice: \"\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    sget-object v3, Landroid/os/Build;->MODEL:Ljava/lang/String;\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    const-string v3, \" / Android \"\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    sget-object v3, Landroid/os/Build$VERSION;->RELEASE:Ljava/lang/String;\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    const-string v3, \"\\n\\n\"\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    invoke-static {p2}, Landroid/util/Log;->getStackTraceString(Ljava/lang/Throwable;)Ljava/lang/String;\n"
            + "    move-result-object v3\n"
            + "    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n"
            + "    move-result-object v2\n"
            + "    const-string v3, \"CREMUX_CRASH\"\n"
            + "    invoke-static {v3, v2}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I\n"
            + "\n"
            + "    new-instance v3, Ljava/lang/StringBuilder;\n"
            + "    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V\n"
            + "    const-string v4, \"crash_\"\n"
            + "    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    invoke-virtual {v3, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    const-string v4, \".txt\"\n"
            + "    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n"
            + "    move-result-object v3\n"
            + "    invoke-direct {p0, v2}, Lcremux/CrashLog;->own(Ljava/lang/String;)V\n"
            + "    invoke-direct {p0, v2, v3}, Lcremux/CrashLog;->pub(Ljava/lang/String;Ljava/lang/String;)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "\n"
            + ".method public uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V\n"
            + "    .locals 2\n"
            + "\n"
            + "    :try_start_0\n"
            + "    invoke-direct {p0, p1, p2}, Lcremux/CrashLog;->dump(Ljava/lang/Thread;Ljava/lang/Throwable;)V\n"
            + "\n"
            + "    :try_end_0\n"
            + "    .catchall {:try_start_0 .. :try_end_0} :catchall_0\n"
            + "\n"
            + "    :catchall_0\n"
            + "    iget-object v0, p0, Lcremux/CrashLog;->prev:Ljava/lang/Thread$UncaughtExceptionHandler;\n"
            + "\n"
            + "    if-eqz v0, :cond_0\n"
            + "\n"
            + "    invoke-interface {v0, p1, p2}, Ljava/lang/Thread$UncaughtExceptionHandler;->uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V\n"
            + "\n"
            + "    return-void\n"
            + "\n"
            + "    :cond_0\n"
            + "    invoke-static {}, Landroid/os/Process;->myPid()I\n"
            + "\n"
            + "    move-result v0\n"
            + "\n"
            + "    invoke-static {v0}, Landroid/os/Process;->killProcess(I)V\n"
            + "\n"
            + "    return-void\n"
            + ".end method\n"
            + "SMEOF\n"
            + "cat > \"$OUT/.inject.py\" <<'PYEOF'\n"
            + "import os, re, sys\n"
            + "root, src = sys.argv[1], sys.argv[2]\n"
            + "smali = open(src, encoding='utf-8').read()\n"
            + "man = os.path.join(root, 'AndroidManifest.xml')\n"
            + "with open(man, encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "    t = f.read()\n"
            + "dirs = sorted(d for d in os.listdir(root) if d == 'smali' or d.startswith('smali_classes'))\n"
            + "if not dirs:\n"
            + "    print('[crashlog] no smali folder, skipped')\n"
            + "    sys.exit(1)\n"
            + "call = '    invoke-static {p1}, Lcremux/CrashLog;->install(Landroid/content/Context;)V\\n\\n'\n"
            + "def add_method(sup):\n"
            + "    return ('\\n.method protected attachBaseContext(Landroid/content/Context;)V\\n    .locals 0\\n\\n' + call\n"
            + "            + '    invoke-super {p0, p1}, ' + sup + '->attachBaseContext(Landroid/content/Context;)V\\n\\n    return-void\\n.end method\\n')\n"
            + "def hook(path):\n"
            + "    with open(path, encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "        s = f.read()\n"
            + "    mm = re.search(r'^\\.method [^\\n]*attachBaseContext\\(Landroid/content/Context;\\)V[ \\t]*\\r?\\n', s, re.M)\n"
            + "    if mm:\n"
            + "        end = s.index('.end method', mm.end())\n"
            + "        pos = mm.end()\n"
            + "        inannot = False\n"
            + "        at = -1\n"
            + "        for ln in s[mm.end():end].splitlines(True):\n"
            + "            st = ln.strip()\n"
            + "            if st.startswith('.annotation'):\n"
            + "                inannot = True\n"
            + "            elif st.startswith('.end annotation'):\n"
            + "                inannot = False\n"
            + "            elif not inannot and (st.startswith('.prologue') or st.startswith('.line') or st.startswith(':') or re.match('^[a-z]', st)):\n"
            + "                at = pos\n"
            + "                break\n"
            + "            pos += len(ln)\n"
            + "        if at < 0:\n"
            + "            return False\n"
            + "        s = s[:at] + call + s[at:]\n"
            + "    else:\n"
            + "        sm = re.search(r'^\\.super (L[^;]+;)', s, re.M)\n"
            + "        if not sm:\n"
            + "            return False\n"
            + "        s = s.rstrip('\\n') + '\\n' + add_method(sm.group(1))\n"
            + "    with open(path, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "        f.write(s)\n"
            + "    return True\n"
            + "def put(rel, text):\n"
            + "    p = os.path.join(root, dirs[0], rel)\n"
            + "    os.makedirs(os.path.dirname(p), exist_ok=True)\n"
            + "    with open(p, 'w', encoding='utf-8', newline='') as f:\n"
            + "        f.write(text)\n"
            + "m = re.search(r'<application\\b[^>]*>', t)\n"
            + "if not m:\n"
            + "    print('[crashlog] no <application> in the manifest, skipped')\n"
            + "    sys.exit(1)\n"
            + "tag = m.group(0)\n"
            + "nm = re.search(r'android:name=\"([^\"]+)\"', tag)\n"
            + "if nm:\n"
            + "    cls = nm.group(1)\n"
            + "    pk = re.search(r'\\bpackage=\"([^\"]+)\"', t)\n"
            + "    if cls.startswith('.') and pk:\n"
            + "        cls = pk.group(1) + cls\n"
            + "    elif '.' not in cls and pk:\n"
            + "        cls = pk.group(1) + '.' + cls\n"
            + "    rel = cls.replace('.', '/') + '.smali'\n"
            + "    hit = [os.path.join(root, d, rel) for d in dirs if os.path.isfile(os.path.join(root, d, rel))]\n"
            + "    if not hit or not hook(hit[0]):\n"
            + "        print('[crashlog] could not hook the Application class %s, skipped' % cls)\n"
            + "        sys.exit(1)\n"
            + "    print('[crashlog] hooked %s' % cls)\n"
            + "else:\n"
            + "    put('cremux/CrashApp.smali', '.class public Lcremux/CrashApp;\\n.super Landroid/app/Application;\\n\\n.method public constructor <init>()V\\n    .locals 0\\n\\n    invoke-direct {p0}, Landroid/app/Application;-><init>()V\\n\\n    return-void\\n.end method\\n' + add_method('Landroid/app/Application;'))\n"
            + "    t = t[:m.start()] + tag.replace('<application', '<application android:name=\"cremux.CrashApp\"', 1) + t[m.end():]\n"
            + "    with open(man, 'w', encoding='utf-8', errors='surrogateescape', newline='') as f:\n"
            + "        f.write(t)\n"
            + "    print('[crashlog] added an Application class')\n"
            + "put('cremux/CrashLog.smali', smali)\n"
            + "print('[crashlog] crash logger injected: Download/Cremux/crash_<package>.txt is written when the app crashes')\n"
            + "PYEOF\n"
            + "  python \"$OUT/.inject.py\" \"$BSRC\" \"$OUT/.CrashLog.smali\" || echo \"[crashlog] injection failed, building without it\"\n"
            + "  fi\n"
            + "fi\n"
            + "echo \"[1/3] apktool: building\"\n"
            + "if ! \"${APKT[@]}\" b \"${AAPT[@]}\" \"$BSRC\" -o \"$OUT/unsigned.apk\"; then\n"
            + "  if \"${APKT[@]}\" b --help 2>&1 | grep -q use-aapt1 && command -v aapt >/dev/null 2>&1; then\n"
            + "    echo \"[apktool] retrying with aapt1\"\n"
            + "    \"${APKT[@]}\" b --use-aapt1 \"$BSRC\" -o \"$OUT/unsigned.apk\" || { rm -rf \"$OUT/.work\" \"$SAN\"; echo \"! apktool build failed. Read the errors above.\"; exit 1; }\n"
            + "  else\n"
            + "    rm -rf \"$OUT/.work\" \"$SAN\"; echo \"! apktool build failed. Read the errors above.\"; exit 1\n"
            + "  fi\n"
            + "fi\n"
            + "rm -rf \"$OUT/.work\" \"$SAN\"\n"
            + "echo \"[2/3] aligning\"\n"
            + "if command -v zipalign >/dev/null 2>&1; then\n"
            + "  zipalign -f -p 4 \"$OUT/unsigned.apk\" \"$OUT/aligned.apk\" || exit 1\n"
            + "else\n"
            + "python - \"$OUT/unsigned.apk\" \"$OUT/aligned.apk\" <<'PYEOF' || exit 1\n"
            + "import sys, zipfile\n"
            + "src, dst = sys.argv[1], sys.argv[2]\n"
            + "with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:\n"
            + "    for item in zin.infolist():\n"
            + "        zi = zipfile.ZipInfo(item.filename, item.date_time)\n"
            + "        zi.compress_type = item.compress_type\n"
            + "        zi.external_attr = item.external_attr\n"
            + "        if item.compress_type == zipfile.ZIP_STORED:\n"
            + "            align = 16384 if item.filename.endswith('.so') else 4\n"
            + "            off = zout.fp.tell() + 30 + len(item.filename.encode('utf-8'))\n"
            + "            zi.extra = b'\\0' * ((-off) % align)\n"
            + "        zout.writestr(zi, zin.read(item.filename))\n"
            + "PYEOF\n"
            + "fi\n"
            + "echo \"[3/3] signing\"\n"
            + "java -jar \"$JAR\" -a \"$OUT/aligned.apk\" --allowResign --skipZipAlign -o \"$OUT/signed\" || { echo \"! signing failed\"; exit 1; }\n"
            + "f=\"$(ls \"$OUT\"/signed/*.apk 2>/dev/null | head -1)\"\n"
            + "[ -n \"$f\" ] || { echo \"! no signed APK was produced\"; exit 1; }\n"
            + "name=\"$(basename \"$SRC\")-signed.apk\"\n"
            + "mv \"$f\" \"$OUT/$name\"; rm -rf \"$OUT/signed\" \"$OUT/unsigned.apk\" \"$OUT/aligned.apk\"\n"
            + "echo \"OK $OUT/$name\"\n";

    private boolean mSourceMode;
    /** Absolute path of an apktool folder to pre-select. */
    public static final String EXTRA_FOLDER = "com.termux.app.ai.extra.folder";

    private File mFolder;
    private File mLastApk;
    private String mPcCommands;

    private androidx.appcompat.widget.SwitchCompat mCrashLog;
    private UiKit.Segmented mModeSeg;
    private UiKit.InfoRow mFolderRow;
    private UiKit.ActionButton mRunBtn, mInstallBtn;
    private TextView mModeHint, mStatus, mLog, mResult, mPcLink;
    private View mApkBox, mSrcBox;
    private ProgressBar mProgress;
    private ScrollView mLogScroll;
    private LinearLayout mFailBox;

    private final AtomicBoolean mCancel = new AtomicBoolean(false);
    private volatile boolean mBusy;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mLogText = new StringBuilder();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decompile);
        setupHeader("Rebuild & Sign", null);
        build((LinearLayout) findViewById(R.id.dc_body));
        setMode(false);
        // Folder from REAPER's "Edit, then Rebuild" button, otherwise the one used last time.
        String path = getIntent().getStringExtra(EXTRA_FOLDER);
        if (path == null) path = getSharedPreferences("cremux_rebuild", MODE_PRIVATE).getString("last_folder", null);
        if (path != null) {
            File f = new File(path);
            if (new File(f, "apktool.yml").isFile()) {
                mFolder = f;
                refreshFolder();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) mCancel.set(true);
        mHandler.removeCallbacksAndMessages(null);
    }

    // -------------------------------------------------------------------- build

    private void build(LinearLayout body) {
        // ---- 1 · What did you modify?
        LinearLayout modeCard = UiKit.step(this, 1, "What did you modify?", null);
        mModeSeg = UiKit.segmented(this, new String[]{"Decompiled APK", "Source project"}, i -> setMode(i == 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(14);
        modeCard.addView(mModeSeg.view, sp);
        mModeHint = UiKit.small(this, "");
        modeCard.addView(mModeHint);
        body.addView(modeCard, UiKit.cardParams(this));

        // ---- 2 · Project folder
        LinearLayout folderCard = UiKit.step(this, 2, "Project folder", null);
        mFolderRow = UiKit.infoRow(this, R.drawable.ic_folder_outline);
        mFolderRow.view.setOnClickListener(v -> chooseFolder());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(16);
        folderCard.addView(mFolderRow.view, fp);
        folderCard.addView(new UiKit.ActionButton(this, "Choose folder", R.drawable.ic_search, false, v -> chooseFolder()),
            UiKit.fullWidth(this, 46, 14));
        body.addView(folderCard, UiKit.cardParams(this));

        // ---- 3 · APK mode: build on the phone
        LinearLayout apk = new LinearLayout(this);
        apk.setOrientation(LinearLayout.VERTICAL);
        LinearLayout buildCard = UiKit.step(this, 3, "Build", null);
        mRunBtn = new UiKit.ActionButton(this, "Rebuild & sign APK", R.drawable.ic_wrench, true, v -> onRunClicked());
        mCrashLog = new androidx.appcompat.widget.SwitchCompat(this);
        mCrashLog.setText("Add crash logger to the APK");
        mCrashLog.setTextColor(UiColors.attr(this, R.attr.tx_text));
        mCrashLog.setTextSize(15);
        mCrashLog.setChecked(getSharedPreferences("cremux_rebuild", MODE_PRIVATE).getBoolean("crashlog", false));
        mCrashLog.setOnCheckedChangeListener((b, on) ->
            getSharedPreferences("cremux_rebuild", MODE_PRIVATE).edit().putBoolean("crashlog", on).apply());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(14);
        buildCard.addView(mCrashLog, cl);
        buildCard.addView(UiKit.small(this, "Saves crashes to Download/Cremux. Keep off for the final APK."));
        buildCard.addView(mRunBtn, UiKit.fullWidth(this, 54, 16));
        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        pp.topMargin = dp(14);
        buildCard.addView(mProgress, pp);
        mStatus = UiKit.small(this, "");
        buildCard.addView(mStatus);
        apk.addView(buildCard, UiKit.cardParams(this));

        mLog = new TextView(this);
        mLog.setTypeface(Typeface.MONOSPACE);
        mLog.setTextSize(11);
        mLog.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        mLog.setPadding(dp(14), dp(12), dp(14), dp(12));
        mLog.setTextIsSelectable(true);
        mLogScroll = new UiKit.NestedScroll(this);
        mLogScroll.setBackgroundResource(R.drawable.bg_step_card);
        mLogScroll.setVisibility(View.GONE);
        mLogScroll.addView(mLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        lp.topMargin = dp(10);
        apk.addView(mLogScroll, lp);

        // Shown only after a failed build: copy the error, or hand it to the AI to fix.
        mFailBox = new LinearLayout(this);
        mFailBox.setOrientation(LinearLayout.HORIZONTAL);
        mFailBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams f1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        f1.topMargin = dp(10);
        f1.setMarginEnd(dp(8));
        mFailBox.addView(new UiKit.ActionButton(this, "Ask REAPER (AI)", R.drawable.ic_brand, true, v -> askAiToFix()), f1);
        LinearLayout.LayoutParams f2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        f2.topMargin = dp(10);
        mFailBox.addView(new UiKit.ActionButton(this, "Copy error", R.drawable.ic_clipboard, false, v -> copyError()), f2);
        apk.addView(mFailBox, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mResultCard = UiKit.step(this, 4, "Signed APK", null);
        mResult = new TextView(this);
        mResult.setTextSize(13);
        mResult.setLineSpacing(dp(2), 1f);
        mResult.setTextColor(UiColors.attr(this, R.attr.tx_text));
        mResult.setTextIsSelectable(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        mResultCard.addView(mResult, rp);
        mInstallBtn = new UiKit.ActionButton(this, "Install", R.drawable.ic_download, true, v -> installLast());
        mResultCard.addView(mInstallBtn, UiKit.fullWidth(this, 50, 14));
        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        m1.topMargin = dp(8);
        m1.setMarginEnd(dp(8));
        more.addView(new UiKit.ActionButton(this, "Share", R.drawable.ic_share, false, v -> {
            if (mLastApk != null) ShareKit.share(this, java.util.Collections.singletonList(mLastApk),
                "application/vnd.android.package-archive");
        }), m1);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        m2.topMargin = dp(8);
        more.addView(new UiKit.ActionButton(this, "Open folder", R.drawable.ic_folder_outline, false,
            v -> ShareKit.openDownloads(this)), m2);
        mResultCard.addView(more);
        mResultCard.addView(UiKit.link(this, "Copy file path", v -> {
            if (mLastApk != null) ShareKit.copy(this, "APK path", mLastApk.getPath());
        }));
        mResultCard.setVisibility(View.GONE);
        apk.addView(mResultCard, UiKit.cardParams(this));
        mApkBox = apk;
        body.addView(apk);

        // ---- 3 · Source mode: build elsewhere
        LinearLayout src = new LinearLayout(this);
        src.setOrientation(LinearLayout.VERTICAL);
        LinearLayout where = UiKit.step(this, 3, "Where should it be built?", null);
        where.addView(option(R.drawable.ic_cloud, "Build on GitHub", "Push with GitHub Sync, Actions builds the APK", v -> buildOnGithub()), wrap(16));
        where.addView(option(R.drawable.ic_desktop, "Build on a PC", "Zip to Downloads/Cremux for Android Studio", v -> buildOnPc()), wrap(10));
        mPcLink = UiKit.link(this, "Copy the PC build commands", v -> copyPc());
        mPcLink.setVisibility(View.GONE);
        where.addView(mPcLink);
        src.addView(where, UiKit.cardParams(this));
        mSrcBox = src;
        body.addView(src);
    }

    private LinearLayout mResultCard;

    private LinearLayout.LayoutParams wrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /** A tappable option card: icon tile, bold title, description, chevron. */
    private View option(@DrawableRes int icon, String title, String desc, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_tile);
        row.setPadding(dp(14), dp(14), dp(12), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(l);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(UiColors.attr(this, R.attr.tx_text));
        iv.setBackgroundResource(R.drawable.bg_icon_tile);
        iv.setPadding(dp(11), dp(11), dp(11), dp(11));
        row.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));

        SpannableStringBuilder sb = new SpannableStringBuilder(title + "\n" + desc);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(1.1f), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView tv = new TextView(this);
        tv.setText(sb);
        tv.setTextSize(13);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextColor(UiColors.attr(this, R.attr.tx_text));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(14));
        row.addView(tv, tp);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(UiColors.attr(this, R.attr.tx_text_2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(20), dp(20));
        cp.setMarginStart(dp(6));
        row.addView(chev, cp);
        return row;
    }

    private void setMode(boolean source) {
        if (mBusy) return;
        if (source != mSourceMode) {
            mFolder = null;
            mResultCard.setVisibility(View.GONE);
            mPcLink.setVisibility(View.GONE);
        }
        mSourceMode = source;
        mModeSeg.select(source ? 1 : 0);
        mApkBox.setVisibility(source ? View.GONE : View.VISIBLE);
        mSrcBox.setVisibility(source ? View.VISIBLE : View.GONE);
        mModeHint.setText(source
            ? "Gradle project: build on GitHub or a PC."
            : "Folder with apktool.yml");
        refreshFolder();
    }

    private void rememberFolder() {
        if (mFolder != null && !mSourceMode) {
            getSharedPreferences("cremux_rebuild", MODE_PRIVATE).edit().putString("last_folder", mFolder.getPath()).apply();
        }
    }

    private void refreshFolder() {
        if (mFolder == null) {
            mFolderRow.title.setText("No folder chosen yet");
            mFolderRow.sub.setText(mSourceMode ? "A folder with gradlew or build.gradle" : "A folder with apktool.yml");
        } else {
            mFolderRow.title.setText(mFolder.getName());
            mFolderRow.sub.setText(display(mFolder));
        }
    }

    // ------------------------------------------------------------------ folders

    private static String display(File f) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String p = f.getPath();
        return p.startsWith(home) ? "~" + p.substring(home.length()) : p;
    }

    private static final int REQ_EXT_FOLDER = 4311;

    /** Asks where to look: the terminal's home, or the phone's own storage (Downloads, Documents, an SD card…). */
    private void chooseFolder() {
        if (mBusy) return;
        Picker.show(this, "Where is the project?", new String[]{"Terminal home (~)", "Phone storage…"}, -1, which -> {
            if (which == 0) chooseTermuxFolder(); else chooseExternalFolder();
        });
    }

    private void chooseExternalFolder() {
        boolean all = Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : FileImport.hasStorageAccess(this);
        if (!all) {
            Toast.makeText(this, "Allow \"All files access\" for CRÉMUX, then pick the folder again.", Toast.LENGTH_LONG).show();
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4203);
                }
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
            return;
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXT_FOLDER);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No folder picker available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXT_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        File dir = treeToFile(data.getData());
        if (dir == null || !dir.isDirectory()) {
            Toast.makeText(this, "That location is not a normal folder on the phone's storage.", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean source = mSourceMode;
        new Thread(() -> {
            final List<File> found = new ArrayList<>();
            scan(dir, 0, source, found);
            runOnUiThread(() -> {
                if (isDestroyed() || mBusy) return;
                if (found.isEmpty()) {
                    Toast.makeText(this, source ? "No Gradle project in that folder." : "No apktool.yml in that folder.", Toast.LENGTH_LONG).show();
                } else if (found.size() == 1) {
                    mFolder = found.get(0);
                    refreshFolder();
                    rememberFolder();
                } else {
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = display(found.get(i));
                    Picker.show(this, source ? "Choose a project" : "Choose a decompiled APK folder", labels, -1, which -> {
                        mFolder = found.get(which);
                        refreshFolder();
                        rememberFolder();
                    });
                }
            });
        }, "cremux-rebuild-scan").start();
    }

    /** Maps a storage-picker tree URI ({@code primary:Folder/sub}) to its real path, or null. */
    private static File treeToFile(@NonNull Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int c = id.indexOf(':');
            String vol = c < 0 ? id : id.substring(0, c);
            String rel = c < 0 ? "" : id.substring(c + 1);
            File base = vol.equals("primary") ? Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
            return rel.isEmpty() ? base : new File(base, rel);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void chooseTermuxFolder() {
        if (mBusy) return;
        final boolean source = mSourceMode;
        // The walk can touch thousands of directories (decompiled output), so keep it off the UI thread.
        new Thread(() -> {
            final List<File> found = new ArrayList<>();
            scan(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), 0, source, found);
            runOnUiThread(() -> {
                if (isDestroyed() || mBusy) return;
                if (found.size() == 1) { // nothing to choose between
                    mFolder = found.get(0);
                    refreshFolder();
                    return;
                }
                if (found.isEmpty()) {
                    Toast.makeText(this, source
                        ? "No Gradle project found in your home folder. Put one there (for example with git clone or unzip)."
                        : "No apktool folder found. Decompile an APK with apktool first (Decompile screen).", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = display(found.get(i));
                Picker.show(this, source ? "Choose a project" : "Choose a decompiled APK folder", labels,
                    mFolder == null ? -1 : found.indexOf(mFolder), which -> {
                        mFolder = found.get(which);
                        refreshFolder();
                        rememberFolder();
                    });
            });
        }, "cremux-rebuild-scan").start();
    }

    /** Finds project roots below {@code dir} (not descending into a root once one is found). */
    private static void scan(File dir, int depth, boolean source, List<File> out) {
        if (depth > 4 || out.size() >= 60) return;
        boolean match = source
            ? (new File(dir, "gradlew").isFile() || new File(dir, "settings.gradle").isFile()
                || new File(dir, "settings.gradle.kts").isFile() || new File(dir, "build.gradle").isFile()
                || new File(dir, "build.gradle.kts").isFile())
            : new File(dir, "apktool.yml").isFile();
        if (match) {
            out.add(dir);
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            String n = k.getName();
            if (!k.isDirectory() || n.startsWith(".") || n.equals("usr") || n.equals("storage") || n.equals("node_modules")) continue;
            scan(k, depth + 1, source, out);
        }
    }

    // ------------------------------------------------------------- APK on phone

    private void onRunClicked() {
        if (mBusy) {
            mCancel.set(true);
            mStatus.setText("Stopping…");
            return;
        }
        if (mFolder == null) {
            Toast.makeText(this, "Choose a folder first.", Toast.LENGTH_SHORT).show();
            return;
        }
        rebuildApk();
    }

    private void rebuildApk() {
        final File src = mFolder;
        final File out = new File(FileImport.reDir(), "rebuilt/" + FileImport.sanitize(src.getName()));
        final File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        final Context app = getApplicationContext();

        if (!FileImport.hasStorageAccess(this)) {
            // Needed only to copy the signed APK to Downloads/Cremux; the build itself works without it, so go on.
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4203);
        }

        rememberFolder();
        mCancel.set(false);
        mBusy = true;
        mLogText.setLength(0);
        mLog.setText("");
        mLogScroll.setVisibility(View.VISIBLE);
        mFailBox.setVisibility(View.GONE);
        mResultCard.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mRunBtn.setLabel("Stop");
        mRunBtn.setIcon(R.drawable.ic_stop);
        mStatus.setText("Building…");

        new Thread(() -> {
            int rc;
            try {
                rc = LiveShell.run(app, APK_SCRIPT, home, this::log, mCancel, src.getPath(), out.getPath(), mCrashLog != null && mCrashLog.isChecked() ? "1" : "0");
            } catch (IOException | RuntimeException e) {
                log("! " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                rc = -1;
            }
            File apk = new File(out, src.getName() + "-signed.apk");
            String copied = null;
            String copyError = null;
            if (rc == 0 && apk.isFile()) {
                try {
                    copied = FileImport.copyToDownloads(apk).getPath();
                } catch (IOException | RuntimeException e) {
                    copyError = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
            final int code = rc;
            if (code != LiveShell.CANCELLED) {
                boolean ok = code == 0 && apk.isFile();
                DoneAlert.notify(this, ok ? "APK ready" : "Rebuild failed", apk.getName(), RebuildActivity.class);
            }
            final String dl = copied;
            final String dlErr = copyError;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                mBusy = false;
                mHandler.removeCallbacks(mLogFlush);
                mLogFlush.run(); // show whatever is still buffered
                mProgress.setVisibility(View.GONE);
                mRunBtn.setLabel("Rebuild & sign APK");
                mRunBtn.setIcon(R.drawable.ic_wrench);
                if (code == LiveShell.CANCELLED) {
                    mStatus.setText("Stopped.");
                } else if (code == 0 && apk.isFile()) {
                    mStatus.setText("Done.");
                    mLastApk = apk;
                    mResult.setText(apk.getName() + "  ·  " + UiKit.size(apk.length()) + "\n"
                        + display(apk) + "\n"
                        + (dl != null ? "Copied to Downloads/Cremux/" + apk.getName() + "\n" : "Could not copy to Downloads: " + dlErr + "\n")
                        + "\nSigned with the standard debug key. If the original app is installed with a different "
                        + "signature, uninstall it first or Android will refuse the update.");
                    mResultCard.setVisibility(View.VISIBLE);
                } else {
                    mStatus.setText("Failed (exit " + code + "). See the log.");
                    mFailBox.setVisibility(View.VISIBLE);
                }
            });
        }, "cremux-rebuild").start();
    }

    /** The last part of the build log, which is where the actual error is. */
    @NonNull
    private String errorText(int maxChars) {
        String t = mLogText.toString().trim();
        return t.length() > maxChars ? t.substring(t.length() - maxChars) : t;
    }

    private void copyError() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("rebuild error", errorText(MAX_LOG_CHARS)));
        Toast.makeText(this, "Error copied.", Toast.LENGTH_SHORT).show();
    }

    /** Opens the AI chat with the failed build and its error already typed in. */
    private void askAiToFix() {
        if (mFolder == null) return;
        Intent i = new Intent(this, AiChatActivity.class);
        i.putExtra(AiChatActivity.EXTRA_PROMPT, "Rebuilding the apktool project " + mFolder.getPath() + " failed. "
            + "Find the cause in that project (the files named in the error), fix it with the smallest possible edit, "
            + "then run rebuild_apk on it again and tell me what you changed. Error log (end of the build output):\n\n"
            + errorText(3500));
        startActivity(i);
    }

    private void installLast() {
        if (mLastApk == null || !mLastApk.isFile()) return;
        ShareKit.install(this, mLastApk);
    }

    private final StringBuilder mPendingLog = new StringBuilder();
    private final AtomicBoolean mLogScheduled = new AtomicBoolean(false);
    private final Runnable mLogFlush = new Runnable() {
        @Override
        public void run() {
            mLogScheduled.set(false);
            synchronized (mPendingLog) {
                mLogText.append(mPendingLog);
                mPendingLog.setLength(0);
            }
            if (mLogText.length() > MAX_LOG_CHARS) mLogText.delete(0, mLogText.length() - MAX_LOG_CHARS);
            mLog.setText(mLogText);
            mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    };

    /** Called from the shell's reader thread for every line; the text view is refreshed at most 5 times a second. */
    private void log(@NonNull String line) {
        synchronized (mPendingLog) {
            mPendingLog.append(line).append('\n');
        }
        if (mLogScheduled.compareAndSet(false, true)) mHandler.postDelayed(mLogFlush, 200);
    }

    // ----------------------------------------------------------- source project

    private static final ZipSplitter.DirFilter BUILD_FILTER = dir -> {
        String n = dir.getName();
        if (n.equals(".gradle") || n.equals(".idea") || n.equals(".git") || n.equals(".cxx") || n.equals("captures")) return true;
        File parent = dir.getParentFile();
        return n.equals("build") && parent != null
            && (new File(parent, "build.gradle").isFile() || new File(parent, "build.gradle.kts").isFile());
    };

    private boolean needFolder() {
        if (mBusy) return false;
        if (mFolder == null) {
            Toast.makeText(this, "Choose a project folder first.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void buildOnGithub() {
        if (!needFolder()) return;
        final File project = mFolder;
        mBusy = true;
        mStatus.setText("");
        Toast.makeText(this, "Preparing the project…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            File zip = null;
            String note = "";
            try {
                note = ensureWorkflow(project);
                File zips = new File(FileImport.reDir(), "zips");
                ZipSplitter.Result r = ZipSplitter.zipFolder(project, zips, FileImport.sanitize(project.getName()) + "-src",
                    6, 0, null, new AtomicBoolean(false), BUILD_FILTER);
                zip = r.parts.get(0);
            } catch (IOException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final File z = zip;
            final String err = error;
            final String n = note;
            runOnUiThread(() -> {
                mBusy = false;
                if (isDestroyed()) return;
                if (err != null) {
                    Toast.makeText(this, "Could not prepare the project: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, n + " Choose or create a repo, then Push. The APK appears under workflow runs.",
                    Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, GitHubSyncActivity.class)
                    .putExtra(GitHubSyncActivity.EXTRA_SOURCE_ZIP, z.getPath()));
            });
        }, "cremux-rebuild-gh").start();
    }

    /** Adds a Gradle build workflow to the project unless it already has one. */
    @NonNull
    private static String ensureWorkflow(@NonNull File project) throws IOException {
        File dir = new File(project, ".github/workflows");
        File[] existing = dir.listFiles((d, n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
        if (existing != null && existing.length > 0) return "Using the workflow already in the project.";
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create .github/workflows");
        boolean wrapper = new File(project, "gradlew").isFile();
        String yml = "name: Crémux build\n"
            + "on:\n  workflow_dispatch:\n  push:\n"
            + "jobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: 17\n"
            + (wrapper
                ? "      - run: chmod +x ./gradlew\n      - run: ./gradlew assembleDebug --stacktrace\n"
                : "      - uses: gradle/actions/setup-gradle@v4\n      - run: gradle assembleDebug --stacktrace\n")
            + "      - uses: actions/upload-artifact@v4\n        with:\n          name: apk\n"
            + "          path: '**/build/outputs/apk/**/*.apk'\n";
        FileWriter w = new FileWriter(new File(dir, "cremux-build.yml"));
        try {
            w.write(yml);
        } finally {
            w.close();
        }
        return "Added .github/workflows/cremux-build.yml to build the APK.";
    }

    private void buildOnPc() {
        if (!needFolder()) return;
        final File project = mFolder;
        mBusy = true;
        Toast.makeText(this, "Zipping the project…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            File copy = null;
            String base = FileImport.sanitize(project.getName());
            try {
                File zips = new File(FileImport.reDir(), "zips");
                ZipSplitter.Result r = ZipSplitter.zipFolder(project, zips, base + "-src", 6, 0, null,
                    new AtomicBoolean(false), BUILD_FILTER);
                copy = FileImport.copyToDownloads(r.parts.get(0));
            } catch (IOException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final File c = copy;
            final String err = error;
            final String b = base;
            runOnUiThread(() -> {
                mBusy = false;
                if (isDestroyed()) return;
                if (err != null) {
                    Toast.makeText(this, "Could not create the zip: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                mPcCommands = "unzip " + b + "-src.zip -d " + b + " && cd " + b + "\n"
                    + "./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug\n"
                    + "# APK: app/build/outputs/apk/debug/app-debug.apk   (needs JDK 17 and the Android SDK)";
                mPcLink.setVisibility(View.VISIBLE);
                Picker.Sheet sheet = Picker.sheet(this, "Project zipped", "Saved to " + c.getPath());
                TextView t = new TextView(this);
                t.setText("Send it to your PC (USB, cloud drive, nearby share), unzip it and run:\n\n" + mPcCommands
                    + "\n\nOr open the unzipped folder in Android Studio and press Build.");
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(12);
                t.setTextIsSelectable(true);
                t.setTextColor(UiColors.attr(this, R.attr.tx_text));
                t.setPadding(dp(24), dp(8), dp(24), dp(16));
                sheet.body.addView(t);
                sheet.body.addView(UiKit.button(this, "Copy commands", true, v -> {
                    copyPc();
                    sheet.dismiss();
                }), UiKit.fullWidth(this, 48, 0));
                sheet.show();
            });
        }, "cremux-rebuild-pc").start();
    }

    private void copyPc() {
        if (mPcCommands == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("build commands", mPcCommands));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ helpers
}
