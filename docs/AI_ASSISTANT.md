# Reverse-engineering tools: two ways to work

Both live on the Home screen and you can use either, or both on the same file.

| | **AI Assistant** (chat) | **REAPER** (tap-through) |
|---|---|---|
| How | You describe the goal; the model runs commands for you (you approve each) | You pick a file, tick tools, press Decompile |
| Best for | "What does this binary do?", digging into one function | Getting the full decompiled output out, then archiving it |
| Needs an API key | yes | no |

Inside the chat, the **Decompile (GUI)** chip opens the second screen; on the Decompile screen,
**Ask the AI about this result** hands the output folder to the chat.

## REAPER (no AI needed)

1. **File** – *Choose file…* (system picker; it is copied to `~/re/`) or *From ~/re…*.
2. **Tools** – jadx (APK/DEX/JAR → Java), apktool (APK → smali + resources), Ghidra (native → C),
   radare2 (headers, imports, strings, functions), Quick triage. Sensible ones are pre-ticked for the file type;
   tools that are not installed are greyed out and offer to install. They run one after another with a live log,
   elapsed time and a Stop button. Output goes to `~/re/out/<name>/<tool>/`.
3. **Zip the result** – choose compression (**Maximum** = Deflate level 9, Normal, Fast, Store) and a
   **part size in MB** (chips for 10/25/30/50/100 or any number; 0 = one file). A 90 MB result with 30 MB parts
   gives `name.zip.001`, `.002`, `.003`. Parts are slices of one ordinary zip: 7-Zip, ZArchiver and WinRAR open the
   `.001` directly, or join them with `cat -- name.zip.* > name.zip` (the screen can copy that command).
   Zips are saved in `~/re/zips/` and optionally copied to `Download/Cremux/` (needs the Storage permission,
   which the app asks for).

Limits: at most 65,535 files and 4 GB per zip (Android's zip writer has no ZIP64) and 999 parts. The screen
tells you if you hit one; zip a single tool's folder in that case.

---

# AI Assistant (reverse-engineering helper)

An in-app chat where a model of your choice can run commands in the Termux environment
(radare2, jadx, apktool, Ghidra, anything from `pkg`) and explain what it finds.

Open it from **Home → AI Assistant**, or long-press the terminal → **Ask AI about this output**.

## Providers (bring your own key)

| Preset | Wire format | Key |
|---|---|---|
| OpenAI | OpenAI chat completions | yes |
| Anthropic | Messages API | yes |
| Gemini | generateContent | yes |
| OpenRouter | OpenAI-compatible | yes |
| Ollama (local) | OpenAI-compatible, `http://127.0.0.1:11434/v1` | no |
| Custom | OpenAI-compatible (LM Studio, vLLM, llama.cpp, proxies) | optional |

Base URL, model and key are remembered per preset. Keys are encrypted with an Android Keystore
AES-GCM key and never leave the device except in requests to the provider you chose.
Tap **Fetch models** to list the models your key can use, **Test connection** to verify.
Plain `http://` is allowed only for `localhost`, `127.0.0.1` and `10.0.2.2`
(`res/xml/network_security_config.xml`); use `https://` for anything remote.

The chosen model must support **tool calling**. Small local models often do not.

## What the model can do

- `run_command` – bash in the Termux environment (headless, no stdin, timeout 60 s default / 600 s max,
  output truncated to first and last 6000 characters).
- `read_file` – read a text file or list a folder; refuses binaries and credential files
  (`~/.ssh`, `*.pem`, `shared_prefs`, …).

### Approval model

- Default: **every command is shown and needs your tap** (Run / Deny / Allow all this chat).
- Optional **Run commands without asking**: harmless-looking commands run immediately, but
  anything that deletes, overwrites, moves data off the device, pipes into a shell or touches
  credentials still asks. This is a best-effort pattern filter (`AiSafety.java`), **not a sandbox**.
- Text inside analysed files is untrusted; the system prompt tells the model not to follow it,
  but prompt injection from a hostile sample is a real risk, which is why approval is the default.

## RE toolkit

**Install RE tools** (chat chip or settings) writes `re-setup` into `$PREFIX/bin` and runs it in the terminal:

- `re-setup core` – radare2, binutils, jadx, apktool, gdb, strace, xxd, file… and `re-triage <file>`.
- `re-setup all` – plus Ghidra headless in a proot Ubuntu, `ghidra-headless`, and
  `re-decompile <binary> [--list | function | 0xaddr]` (analysis is cached under `~/.re`).

Notes: Ghidra needs a 64-bit phone, several hundred MB, and a few GB of RAM. Releases ship no native
decompiler for ARM64 Linux, so the script builds it once (5–15 min). If Ghidra fails, radare2 and jadx still work.
**Attach file** copies a file from the picker into `~/re/` and pre-fills a prompt.

## Layout

```
app/src/main/java/com/termux/app/ai/
  AiClient      three provider adapters + model listing (pure Java)
  AiAgent       model <-> tool loop, cancellation, approval hook (pure Java)
  AiTools       run_command / read_file
  AiSafety      risky-command filter, secret-path filter, output capture
  AiConfig      per-provider settings, Keystore-encrypted keys
  AiChatActivity / AiSettingsActivity / ReToolkit / AiChatState
  DecompileActivity   the tap-through screen (file, tools, live log, zip options)
  DecompileTools      what each tool runs; which are suggested for which file type
  LiveShell           streams a script's output line by line; Stop ends the whole process tree
  ZipSplitter         streaming zip -> fixed-size parts, no second copy on disk
app/src/main/res/raw/cremux_re_setup.sh    installer + helper scripts
```

Not implemented yet: streaming responses, persisted chat history, image input.
