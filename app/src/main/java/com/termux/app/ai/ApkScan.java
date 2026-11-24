package com.termux.app.ai;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A quick look inside an APK (zip central directory only, nothing is unpacked) so the Decompile screen can pick
 * tools sensibly and tell the user up front what static tools will and will not show.
 */
final class ApkScan {

    private static final String[] PACKERS = {
        "libjiagu", "libsecexe", "libsecmain", "libsecshell", "libdexhelper", "libchaosvmp", "libprotectclass",
        "libbaiduprotect", "libnqshield", "libmobisec", "libijiami", "libegis", "libapkprotect", "libshella",
        "libshellx", "libddog", "libtup", "libcovault", "libdxbase", "libkdp", "libvdog",
    };

    boolean valid;
    boolean split;
    int nativeLibs;
    String bestAbi = "";
    int dexCount;
    String packer = "";
    String framework = "";
    boolean resObfuscated;

    private ApkScan() { }

    @NonNull
    static ApkScan scan(@NonNull File f) {
        ApkScan r = new ApkScan();
        String n = f.getName().toLowerCase(Locale.US);
        r.split = n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm");
        if (!(n.endsWith(".apk") || r.split || n.endsWith(".zip") || n.endsWith(".aab"))) return r;
        try (ZipFile z = new ZipFile(f)) {
            r.valid = true;
            java.util.Map<String, Integer> abis = new java.util.LinkedHashMap<>();
            int shortResDirs = 0;
            java.util.Set<String> seenResDirs = new java.util.HashSet<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                String lower = name.toLowerCase(Locale.US);
                if (lower.matches("classes\\d*\\.dex")) r.dexCount++;
                if (lower.startsWith("lib/") && lower.endsWith(".so")) {
                    String[] p = name.split("/");
                    if (p.length >= 3) {
                        Integer c = abis.get(p[1]);
                        abis.put(p[1], c == null ? 1 : c + 1);
                    }
                    String file = p[p.length - 1].toLowerCase(Locale.US);
                    if (r.packer.isEmpty()) for (String k : PACKERS) if (file.startsWith(k)) r.packer = k.substring(3);
                    if (file.equals("libflutter.so") || file.equals("libapp.so")) r.framework = "Flutter";
                    else if (file.equals("libhermes.so") || file.equals("libreactnativejni.so")) r.framework = "React Native";
                    else if (file.equals("libil2cpp.so")) r.framework = "Unity (IL2CPP)";
                    else if (file.equals("libunity.so") && r.framework.isEmpty()) r.framework = "Unity";
                    else if (file.equals("libmonodroid.so") || file.equals("libmonosgen-2.0.so")) r.framework = "Xamarin";
                }
                if (lower.equals("assets/index.android.bundle") && r.framework.isEmpty()) r.framework = "React Native";
                if (lower.startsWith("assemblies/") && r.framework.isEmpty()) r.framework = "Xamarin";
                if (lower.startsWith("assets/www/") && r.framework.isEmpty()) r.framework = "Cordova / web";
                if (r.packer.isEmpty() && (lower.startsWith("assets/ijiami") || lower.startsWith("assets/libjiagu")
                    || lower.startsWith("assets/secdata") || lower.startsWith("assets/dex.dat"))) r.packer = "asset-packed dex";
                // AndResGuard-style resource obfuscation: res/ folders named with one or two characters.
                if (lower.startsWith("res/")) {
                    int s = lower.indexOf('/', 4);
                    if (s > 4) {
                        String dir = lower.substring(4, s);
                        if (seenResDirs.add(dir) && dir.length() <= 2 && !dir.equals("xml")) shortResDirs++;
                    }
                }
            }
            r.resObfuscated = shortResDirs >= 5;
            for (java.util.Map.Entry<String, Integer> e : abis.entrySet()) r.nativeLibs += e.getValue();
            for (String abi : android.os.Build.SUPPORTED_ABIS) if (abis.containsKey(abi)) { r.bestAbi = abi; break; }
            if (r.bestAbi.isEmpty() && !abis.isEmpty()) r.bestAbi = abis.keySet().iterator().next();
        } catch (java.io.IOException | RuntimeException e) {
            r.valid = false;
        }
        return r;
    }

    /** One-line summary for under the file name, empty when there is nothing worth saying. */
    @NonNull
    String summary() {
        if (!valid) return "";
        List<String> p = new ArrayList<>();
        if (split) p.add("split bundle");
        if (dexCount > 1) p.add(dexCount + " dex files");
        if (nativeLibs > 0) p.add(nativeLibs + " native libs (" + bestAbi + ")");
        if (!framework.isEmpty()) p.add(framework);
        if (!packer.isEmpty()) p.add("packed: " + packer);
        if (resObfuscated) p.add("obfuscated resources");
        return String.join(" · ", p);
    }

    /** Plain-language advice that matches what the chosen tools can and cannot show for this file. */
    @NonNull
    List<String> advice() {
        List<String> a = new ArrayList<>();
        if (!valid) return a;
        if (!packer.isEmpty()) {
            a.add("Packed with " + packer + ": the real code is unpacked at runtime, so jadx and apktool will mostly show the "
                + "loader. Dump the decrypted dex from a running app (for example with Frida) and decompile that.");
        }
        switch (framework) {
            case "Flutter":
                a.add("Flutter app: the Dart code is compiled into libapp.so. jadx/apktool only show the thin Android shell; "
                    + "analyse libapp.so (Native libs step) or use a Flutter-specific tool such as reFlutter.");
                break;
            case "React Native":
                a.add("React Native app: the logic is JavaScript in assets/index.android.bundle (often Hermes bytecode). "
                    + "Read the bundle directly; jadx only shows the native shell.");
                break;
            case "Unity (IL2CPP)":
                a.add("Unity IL2CPP app: game logic is in libil2cpp.so with global-metadata.dat. Use Il2CppDumper on those two files.");
                break;
            case "Unity":
            case "Xamarin":
                a.add(framework + " app: the game/app code is in managed .NET assemblies (assets/bin/Data/Managed or assemblies/), "
                    + "not in the dex. Open them with a .NET decompiler.");
                break;
            default:
                break;
        }
        if (resObfuscated) {
            a.add("Resource names are obfuscated (res/ folders with 1-2 letter names): apktool decodes them, but rebuilding may need care.");
        }
        if (nativeLibs > 0 && framework.isEmpty() && packer.isEmpty()) {
            a.add("Has native libraries: the Native libs step lists imports, exports and strings of each .so (" + bestAbi + ").");
        }
        return a;
    }
}
