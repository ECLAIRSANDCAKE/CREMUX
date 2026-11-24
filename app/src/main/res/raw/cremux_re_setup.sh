#!/data/data/com.termux/files/usr/bin/bash
# Crémux reverse-engineering toolkit installer.
#
#   re-setup core     radare2, jadx, apktool, binutils, gdb, strace + the re-triage helper
#   re-setup ghidra   Ghidra headless inside a proot Ubuntu + the re-decompile helper (large download)
#   re-setup all      both
#   re-setup linux [distro]   a full Linux (proot-distro): debian (default), ubuntu, alpine, archlinux, fedora
#
# Safe to run again: it skips what is already installed.
set -u

MODE="${1:-core}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
BIN="$PREFIX/bin"
RE="$HOME/.re"
DISTRO="ubuntu"

T_START="$(date +%s)"

human_bytes() {
  awk -v b="${1:-0}" 'BEGIN { if (b >= 1073741824) printf "%.2f GB", b/1073741824;
                              else if (b >= 1048576) printf "%.1f MB", b/1048576;
                              else printf "%.0f KB", b/1024 }'
}
human_time() {
  local s="${1:-0}"; s="${s%.*}"; [ -z "$s" ] && s=0
  if   [ "$s" -ge 3600 ]; then printf '%dh %02dm' $((s/3600)) $((s%3600/60))
  elif [ "$s" -ge 60 ];   then printf '%dm %02ds' $((s/60)) $((s%60))
  else printf '%ds' "$s"; fi
}

say()  { printf '\n\033[1m==> %s\033[0m  \033[2m(elapsed %s)\033[0m\n' "$*" "$(human_time $(( $(date +%s) - T_START )))"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
warn() { printf '  \033[33mskip\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mfail\033[0m  %s\n' "$*"; }

# ------------------------------------------------------------- speed and ETA

SPEED=0   # measured download speed, bytes per second

# Time a ~3 MB download from the package mirror to learn how fast this connection is.
probe_speed() {
  command -v curl >/dev/null 2>&1 || return 0
  local mirror arch url
  mirror="$(cat "$PREFIX"/etc/apt/sources.list "$PREFIX"/etc/apt/sources.list.d/*.list 2>/dev/null \
            | grep '^deb ' | head -1 | awk '{print $2}')"
  arch="$(dpkg --print-architecture 2>/dev/null)"
  [ -n "$mirror" ] || return 0
  for url in "$mirror/dists/stable/main/binary-$arch/Packages.gz" "$mirror/dists/stable/main/binary-$arch/Packages"; do
    SPEED="$(curl -fsL -r 0-3145727 --max-time 12 -o /dev/null -w '%{speed_download}' "$url" 2>/dev/null | cut -d. -f1)"
    case "$SPEED" in ''|*[!0-9]*) SPEED=0 ;; esac
    [ "$SPEED" -gt 0 ] && return 0
  done
}

# estimate <label> <bytes> [extra seconds that are not download time]
estimate() {
  local label="$1" bytes="${2:-0}" extra="${3:-0}"
  if [ "$SPEED" -gt 0 ] && [ "$bytes" -gt 0 ]; then
    printf '  %s: %s at %s/s  ->  about %s\n' "$label" "$(human_bytes "$bytes")" \
      "$(human_bytes "$SPEED")" "$(human_time $(( bytes / SPEED + extra )))"
  elif [ "$bytes" -gt 0 ]; then
    printf '  %s: %s (speed unknown, the ETA appears once the download starts)\n' "$label" "$(human_bytes "$bytes")"
  fi
}

# draw_bar <done bytes> <total bytes> <start epoch>
draw_bar() {
  local d="$1" t="$2" t0="$3" now el rate pct fill eta cols line
  [ "$t" -le 0 ] && return 0
  [ "$d" -gt "$t" ] && d="$t"
  now="$(date +%s)"; el=$(( now - t0 )); [ "$el" -lt 1 ] && el=1
  rate=$(( d / el ))
  # until enough has arrived to trust the live rate, lean on the speed test
  if [ "$d" -lt 1048576 ] && [ "$SPEED" -gt 0 ]; then rate="$SPEED"; fi
  pct=$(( d * 100 / t )); fill=$(( pct * 10 / 100 ))
  if [ "$rate" -gt 0 ]; then eta="$(human_time $(( (t - d) / rate )))"; else eta="--"; fi
  # Keep the whole line shorter than the terminal width: a wrapped line cannot be erased by \r,
  # so every redraw would scroll the screen instead of updating in place.
  cols="$(stty size 2>/dev/null | awk '{print $2}')"; cols="${cols:-${COLUMNS:-40}}"
  [ "$cols" -gt 0 ] 2>/dev/null || cols=40
  line="$(printf '[%s%s] %3d%% %s/%s ETA %s' \
    "$(printf '%*s' "$fill" '' | tr ' ' '#')" "$(printf '%*s' $(( 10 - fill )) '' | tr ' ' '.')" \
    "$pct" "$(human_bytes "$d")" "$(human_bytes "$t")" "$eta")"
  if [ "${#line}" -gt $(( cols - 3 )) ]; then
    line="$(printf '[%s%s] %3d%% ETA %s' \
      "$(printf '%*s' "$fill" '' | tr ' ' '#')" "$(printf '%*s' $(( 10 - fill )) '' | tr ' ' '.')" "$pct" "$eta")"
  fi
  printf '\r\033[K %s' "${line:0:$(( cols - 3 ))}"
}

# Bytes apt would still download to install these packages (0 if unknown).
pkg_bytes() {
  apt-get install --print-uris -qq -y "$@" 2>/dev/null | awk "/^'/ { s += \$3 } END { print s + 0 }"
}

cache_bytes() { du -sb "$PREFIX/var/cache/apt/archives" 2>/dev/null | cut -f1; }

install_pkgs() {
  for p in "$@"; do
    if dpkg -s "$p" >/dev/null 2>&1; then
      ok "$p (already installed)"
    elif pkg install -y "$p" >/dev/null 2>&1; then
      ok "$p"
    else
      warn "$p (not available in your package repo, or the install failed)"
    fi
  done
}

# Install packages one by one with a live progress bar, speed and ETA.
install_pkgs_live() {
  local p todo=() usable=() total done_b=0 size base now t0 pid cur
  for p in "$@"; do
    if dpkg -s "$p" >/dev/null 2>&1; then ok "$p (already installed)"
    elif apt-cache show "$p" >/dev/null 2>&1; then todo+=("$p")
    else warn "$p (not available in your package repo)"; fi
  done
  [ "${#todo[@]}" -eq 0 ] && return 0

  total="$(pkg_bytes "${todo[@]}")"; total="${total:-0}"
  estimate "Download" "$total"
  t0="$(date +%s)"
  for p in "${todo[@]}"; do
    size="$(pkg_bytes "$p")"; size="${size:-0}"
    base="$(cache_bytes)"; base="${base:-0}"
    pkg install -y "$p" >/dev/null 2>&1 &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
      cur="$(cache_bytes)"; cur=$(( ${cur:-0} - base ))
      [ "$cur" -lt 0 ] && cur=0; [ "$cur" -gt "$size" ] && cur="$size"
      draw_bar $(( done_b + cur )) "$total" "$t0"
      sleep 1
    done
    if wait "$pid"; then
      done_b=$(( done_b + size )); draw_bar "$done_b" "$total" "$t0"; printf '\n'
      ok "$p"
    else
      printf '\n'; warn "$p (the install failed)"
    fi
  done
  printf '\n'
}

# --------------------------------------------------------------------------- core

install_core() {
  say "Updating package lists"
  pkg update -y >/dev/null 2>&1 || warn "pkg update failed (check your connection)"

  say "Measuring your connection speed"
  probe_speed
  if [ "$SPEED" -gt 0 ]; then ok "download speed about $(human_bytes "$SPEED")/s"
  else warn "could not measure the speed (the ETA will appear once downloads start)"; fi

  say "Installing core reverse-engineering tools"
  install_pkgs_live radare2 binutils file xxd unzip zip curl git python jadx apktool aapt2 zipalign gdb strace

  say "Installing the re-triage helper"
  mkdir -p "$RE"
  cat > "$BIN/re-triage" <<'TRIAGE_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# re-triage <file>: a quick, cheap first look at an unknown file.
f="${1:-}"
if [ -z "$f" ] || [ ! -f "$f" ]; then echo "usage: re-triage <file>"; exit 1; fi
echo "== file";   file "$f"
echo "== size";   ls -l "$f" | awk '{print $5 " bytes"}'
echo "== sha256"; sha256sum "$f" | cut -d' ' -f1
kind="$(file -b "$f")"
case "$kind" in
  *ELF*|*PE32*|*Mach-O*)
    if command -v rabin2 >/dev/null 2>&1; then
      echo "== info";    rabin2 -I "$f" 2>/dev/null | head -40
      echo "== imports"; rabin2 -i "$f" 2>/dev/null | head -60
      echo "== exports"; rabin2 -E "$f" 2>/dev/null | head -30
      echo "== strings (first 60 of length >= 6)"; rabin2 -zz "$f" 2>/dev/null | awk 'length($0) > 20' | head -60
    else
      echo "(radare2 not installed: run re-setup core)"
      echo "== strings"; strings -n 6 "$f" | head -60
    fi ;;
  *Zip*|*Java*|*Android*|*archive*)
    echo "== archive contents"; unzip -l "$f" 2>/dev/null | head -60
    echo "(for an APK try: jadx -d out '$f'   or   apktool d '$f')" ;;
  *)
    echo "== strings"; strings -n 6 "$f" | head -60 ;;
esac
TRIAGE_EOF
  chmod +x "$BIN/re-triage"
  ok "re-triage <file>"
}

# ------------------------------------------------------------------------- ghidra

install_ghidra() {
  case "$(uname -m)" in
    aarch64) NATIVE_DIR="linux_arm_64" ;;
    x86_64)  NATIVE_DIR="linux_x86_64" ;;
    *) fail "Ghidra needs a 64-bit device (found $(uname -m)). Use radare2 and jadx instead."; return 1 ;;
  esac

  say "Estimating the Ghidra download"
  [ "$SPEED" -gt 0 ] || probe_speed
  GH_URL="$(curl -fsSL https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/latest 2>/dev/null \
            | grep -o 'https://[^"]*ghidra_[^"]*\.zip' | head -1)"
  GH_BYTES=0
  [ -n "$GH_URL" ] && GH_BYTES="$(curl -sIL "$GH_URL" 2>/dev/null | tr -d '\r' \
            | awk 'tolower($1) == "content-length:" { l = $2 } END { print l + 0 }')"
  [ "$GH_BYTES" -gt 0 ] || GH_BYTES=450000000
  estimate "Ubuntu base system (approx.)" 70000000
  estimate "Java 21 and build tools (approx.)" 230000000
  estimate "Ghidra" "$GH_BYTES"
  estimate "Everything above" $(( 70000000 + 230000000 + GH_BYTES )) 600
  printf '  (the last line includes about 10 minutes for compiling the native decompiler;\n'
  printf '   the downloads below show their own live speed and time left)\n'

  say "Setting up an Ubuntu environment for Ghidra"
  install_pkgs proot-distro
  if ! command -v proot-distro >/dev/null 2>&1; then
    fail "proot-distro is not available."; return 1
  fi
  if [ ! -d "$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO" ]; then
    proot-distro install "$DISTRO" || { fail "could not install $DISTRO"; return 1; }
  else
    ok "$DISTRO (already installed)"
  fi

  say "Installing Java and Ghidra inside $DISTRO (several hundred MB)"
  proot-distro login "$DISTRO" -- bash -c '
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y openjdk-21-jdk-headless unzip curl ca-certificates make g++ file
    if [ ! -x /opt/ghidra/support/analyzeHeadless ]; then
      echo "Looking up the latest Ghidra release..."
      URL="$(curl -fsSL https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/latest | grep -o "https://[^\"]*ghidra_[^\"]*\.zip" | head -1)"
      [ -n "$URL" ] || { echo "Could not find the Ghidra download (GitHub rate limit? try again later)."; exit 1; }
      echo "Downloading $URL"
      curl -fL "$URL" -o /tmp/ghidra.zip
      unzip -q /tmp/ghidra.zip -d /opt
      rm -rf /opt/ghidra
      mv /opt/ghidra_* /opt/ghidra
      rm -f /tmp/ghidra.zip
    fi
    DIR=/opt/ghidra/Ghidra/Features/Decompiler/os/'"$NATIVE_DIR"'
    if [ ! -x "$DIR/decompile" ]; then
      echo "Building the native decompiler for this CPU (5-15 minutes)..."
      cd /opt/ghidra/Ghidra/Features/Decompiler/src/decompile/cpp
      make -j2 ghidra_opt
      mkdir -p "$DIR"
      cp ghidra_opt "$DIR/decompile"
    fi
    echo "Ghidra ready: $(ls /opt/ghidra | head -1 >/dev/null && echo /opt/ghidra)"
  ' || { fail "Ghidra setup failed (see the messages above). radare2 and jadx still work."; return 1; }

  say "Installing the ghidra-headless and re-decompile helpers"
  mkdir -p "$RE/ghidra_scripts" "$RE/in" "$RE/out" "$RE/proj"

  cat > "$BIN/ghidra-headless" <<'GH_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# ghidra-headless <analyzeHeadless args>: your home folder is mounted at /work inside the Ubuntu.
exec proot-distro login ubuntu --shared-tmp --bind "$HOME:/work" -- /opt/ghidra/support/analyzeHeadless "$@"
GH_EOF
  chmod +x "$BIN/ghidra-headless"

  cat > "$RE/ghidra_scripts/DecompileFns.java" <<'JAVA_EOF'
// Crémux: write decompiled C (or a function list) of the current program to a text file.
// Args: <output file> <--list | --all | function name | hex address>
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import java.io.FileWriter;
import java.io.PrintWriter;

public class DecompileFns extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] a = getScriptArgs();
        String outPath = a.length > 0 ? a[0] : "/tmp/decompiled.txt";
        String what = a.length > 1 ? a[1] : "--all";
        boolean list = what.equals("--list");
        boolean all = what.equals("--all");

        long wanted = -1;
        if (!list && !all) {
            try {
                wanted = Long.parseUnsignedLong(what.replaceFirst("^0[xX]", ""), 16);
            } catch (NumberFormatException e) {
                wanted = -1; // not an address: match by name
            }
        }

        DecompInterface di = new DecompInterface();
        if (!list) di.openProgram(currentProgram);
        int n = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(outPath))) {
            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Function f = it.next();
                String addr = f.getEntryPoint().toString();
                if (list) {
                    w.println(addr + "  " + f.getName() + "  (" + f.getBody().getNumAddresses() + " bytes)");
                    n++;
                    continue;
                }
                boolean match = all || f.getName().equals(what)
                    || (wanted >= 0 && f.getEntryPoint().getOffset() == wanted);
                if (!match) continue;
                DecompileResults r = di.decompileFunction(f, 90, monitor);
                w.println("// " + f.getName() + " @ " + addr);
                if (r != null && r.decompileCompleted()) {
                    w.println(r.getDecompiledFunction().getC());
                } else {
                    w.println("// decompilation failed: " + (r == null ? "no result" : r.getErrorMessage()));
                }
                n++;
            }
        }
        di.dispose();
        println("DecompileFns: wrote " + n + " entries to " + outPath);
    }
}
JAVA_EOF

  cat > "$BIN/re-decompile" <<'RD_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# re-decompile <binary> [--list | --all | function-name | 0xADDRESS] [output-file]
# Analyses the binary with Ghidra once (cached), then prints the decompiled C
# (or saves all of it to output-file when one is given).
f="${1:-}"; what="${2:---list}"; dest="${3:-}"
if [ -z "$f" ] || [ ! -f "$f" ]; then echo "usage: re-decompile <binary> [--list | --all | function | 0xaddress] [output-file]"; exit 1; fi
RE="$HOME/.re"
mkdir -p "$RE/in" "$RE/out" "$RE/proj" "$RE/ghidra_scripts"
key="$(sha256sum "$f" | cut -c1-16)"
cp -f "$f" "$RE/in/$key"
out="$RE/out/$key.txt"
log="$RE/out/$key.log"
rm -f "$out"
if [ -f "$RE/proj/$key.gpr" ]; then
  mode=(-process "$key" -noanalysis)
else
  echo "First run for this file: Ghidra is analysing it (this can take a few minutes)..." >&2
  mode=(-import "/work/.re/in/$key")
fi
ghidra-headless /work/.re/proj "$key" "${mode[@]}" \
  -scriptPath /work/.re/ghidra_scripts \
  -postScript DecompileFns.java "/work/.re/out/$key.txt" "$what" >"$log" 2>&1
if [ ! -s "$out" ]; then
  echo "Ghidra produced no output. Last log lines:" >&2
  tail -n 30 "$log" >&2
  exit 1
fi
size="$(wc -c < "$out")"
if [ -n "$dest" ]; then
  mkdir -p "$(dirname "$dest")" && cp -f "$out" "$dest" && echo "Saved $size bytes to $dest"
  exit $?
fi
if [ "$size" -le 30000 ]; then
  cat "$out"
else
  head -n 150 "$out"
  echo "... [$size bytes total; full output saved to $out. Use grep -n / sed -n to read parts]"
fi
RD_EOF
  chmod +x "$BIN/re-decompile"
  ok "ghidra-headless, re-decompile <binary> [--list|function|0xaddr]"
}

# Full Linux userland through proot-distro (no root needed). Adds the re-linux helper.
install_linux() {
  D="${1:-debian}"
  case "$D" in
    debian|ubuntu|alpine|archlinux|fedora) ;;
    *) fail "unknown distro '$D' (use debian, ubuntu, alpine, archlinux or fedora)"; return 1 ;;
  esac
  say "Installing proot-distro"
  install_pkgs proot-distro
  command -v proot-distro >/dev/null 2>&1 || { fail "proot-distro is not available."; return 1; }

  say "Installing $D (a rootfs download, typically 50-200 MB)"
  [ "$SPEED" -gt 0 ] || probe_speed
  estimate "$D root filesystem (approx.)" 120000000
  if proot-distro list 2>/dev/null | grep -qiE "^[^a-z]*$D\b.*installed" \
     || [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/$D" ]; then
    ok "$D (already installed)"
  else
    proot-distro install "$D" || { fail "could not install $D (run: proot-distro list)"; return 1; }
  fi

  say "Installing the re-linux helper"
  echo "$D" > "$RE/linux_distro"
  cat > "$BIN/re-linux" <<'RL_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# re-linux              open a shell inside the Linux
# re-linux <command>    run one command inside it (your home folder is mounted at /work)
D="$(cat "$HOME/.re/linux_distro" 2>/dev/null || echo debian)"
if [ $# -eq 0 ]; then
  exec proot-distro login "$D" --shared-tmp --bind "$HOME:/work" --work-dir /work
fi
exec proot-distro login "$D" --shared-tmp --bind "$HOME:/work" --work-dir /work -- "$@"
RL_EOF
  chmod +x "$BIN/re-linux"

  say "Preparing $D (package index and basic tools)"
  case "$D" in
    debian|ubuntu) re-linux bash -c 'export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends ca-certificates curl wget git file xxd binutils build-essential' ;;
    alpine)        re-linux sh -c 'apk update && apk add bash curl wget git file binutils build-base' ;;
    archlinux)     re-linux bash -c 'pacman -Syu --noconfirm && pacman -S --noconfirm --needed curl wget git file binutils base-devel' ;;
    fedora)        re-linux bash -c 'dnf install -y curl wget git file binutils gcc make' ;;
  esac || warn "the basic tools did not finish installing; open re-linux and retry"
  ok "re-linux (shell)   re-linux <command>   home folder is /work inside"
}

# ---------------------------------------------------------------------------- main

if [ ! -d "$PREFIX" ]; then
  echo "Termux environment not found at $PREFIX"; exit 1
fi

case "$MODE" in
  core)   install_core ;;
  ghidra) install_core; install_ghidra ;;
  all)    install_core; install_ghidra ;;
  linux)  install_linux "${2:-debian}" ;;
  *) echo "usage: re-setup [core|ghidra|all|linux [distro]]"; exit 1 ;;
esac

say "Done"
echo "Try:  re-triage <file>     r2 -q -c 'aaa; afl' <binary>     jadx -d out app.apk"
command -v re-decompile >/dev/null 2>&1 && echo "      re-decompile <binary> --list"
command -v re-linux >/dev/null 2>&1 && echo "      re-linux            (Linux shell)    re-linux <command>"
echo "Then ask the AI Assistant to analyse a file."
