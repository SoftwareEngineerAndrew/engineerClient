package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.engineerclient.practice.TermInfo
import com.google.gson.JsonParser
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns down (or mutes) whatever else is playing on the computer through the parts of a run that need your ears:
 * from "Starting in 3 seconds." (or Mort, if that was missed) until the run reaches 300 score, and from 30 s into
 * Storm until S4 starts. Everything comes back as it was then, on a world change, when this is turned off and when
 * the game closes.
 *
 * Other programs' own volume is what changes, never the system's: on Linux each PulseAudio / PipeWire stream
 * (`pactl`), on Windows each app's mixer volume (a PowerShell helper). The game itself and voice chat (Discord and
 * other call apps, PipeWire's phone/communication streams) are left alone. Streams that start while it's quiet are
 * caught within a few seconds.
 */
object QuietMedia : Module(
    name = "Quiet Media",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Turns down other apps' audio (music, videos) from 3 s before the run until 300 score, and from 30 s into Storm until S4."
) {
    private val amount by NumberSetting("Quiet Amount", 70, 0..100, 5, desc = "How much quieter other apps get: 70% leaves them at 30% of their volume.", unit = "%")
    private val mute by BooleanSetting("Mute", false, desc = "Mutes other apps instead of turning them down.")

    private const val COUNTDOWN = "Starting in 3 seconds."
    private const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_QUIET_TICKS = 30 * 20
    private const val SCORE = 300

    private enum class Phase { NONE, CLEAR, AFTER_CLEAR, STORM_WAIT, STORM, DONE }

    private var phase = Phase.NONE
    private var serverTicks = 0
    private var stormQuietAt = 0
    private var refreshTick = 0

    /** Every audio call off the game's threads, one at a time, in order. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "coffeeclient-quiet-media").apply { isDaemon = true } }
    private val backend: Backend? = when {
        System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") -> WindowsBackend
        System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux") -> LinuxBackend
        else -> null
    }

    private fun quiet() {
        val factor = (100 - amount) / 100.0
        val muted = mute
        worker.execute { CoffeeClient.safely("quiet media") { backend?.quiet(factor, muted) } }
    }

    private fun restore() {
        worker.execute { CoffeeClient.safely("quiet media restore") { backend?.restore() } }
    }

    private fun enter(next: Phase) {
        val wasQuiet = phase == Phase.CLEAR || phase == Phase.STORM
        phase = next
        val isQuiet = next == Phase.CLEAR || next == Phase.STORM
        if (isQuiet && !wasQuiet) quiet() else if (!isQuiet && wasQuiet) restore()
    }

    override fun onDisable() {
        super.onDisable()
        enter(Phase.NONE)
    }

    init {
        if (backend == null) CoffeeClient.logger.info("[cc] Quiet Media: no audio control on ${System.getProperty("os.name")}")
        // A crash or quit while quiet still gives everything back.
        Runtime.getRuntime().addShutdownHook(Thread { try { backend?.restore() } catch (_: Throwable) {} })

        on<MessageEvent.Chat> {
            when (message) {
                COUNTDOWN -> if (phase == Phase.NONE || phase == Phase.DONE) enter(Phase.CLEAR)
                MORT -> if (phase == Phase.NONE) enter(Phase.CLEAR)
                STORM_START -> if (phase != Phase.STORM) { stormQuietAt = serverTicks + STORM_QUIET_TICKS; enter(Phase.STORM_WAIT) }
            }
        }
        on<TickEvent.Server> { serverTicks++ }
        on<TickEvent.End> {
            when (phase) {
                Phase.CLEAR -> if (DungeonUtils.score >= SCORE) enter(Phase.AFTER_CLEAR)
                Phase.STORM_WAIT -> if (serverTicks >= stormQuietAt) enter(Phase.STORM)
                Phase.STORM -> if (TermInfo.sectionNumber() == 4) enter(Phase.DONE)
                else -> {}
            }
            // Streams that started while it's quiet (the next song, a new video).
            if ((phase == Phase.CLEAR || phase == Phase.STORM) && ++refreshTick % (backend?.refreshTicks ?: Int.MAX_VALUE) == 0) quiet()
        }
        on<LevelEvent.Load> { enter(Phase.NONE) }
    }

    /** Turns other programs down and back up. [quiet] again only takes in what started since. */
    private interface Backend {
        val refreshTicks: Int
        fun quiet(factor: Double, mute: Boolean)
        fun restore()
    }

    private fun run(vararg cmd: String, input: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        if (input != null) p.outputStream.bufferedWriter().use { it.write(input) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly()
        return out
    }

    private val ownPid = ProcessHandle.current().pid().toString()

    /** PulseAudio / PipeWire: each sink input's own volume and mute, through pactl. */
    private object LinuxBackend : Backend {
        override val refreshTicks = 100
        private val CALL_APPS = Regex("discord|teamspeak|ts3client|mumble|zoom|teams|skype", RegexOption.IGNORE_CASE)
        /** Sink input -> its channel volumes and mute before it was turned down. */
        private val saved = LinkedHashMap<Int, Pair<List<Long>, Boolean>>()

        @Synchronized
        override fun quiet(factor: Double, mute: Boolean) {
            val ours = JsonParser.parseString(run("pactl", "-f", "json", "list", "clients")).asJsonArray
                .filter { it.asJsonObject.getAsJsonObject("properties")?.get("application.process.id")?.asString == ownPid }
                .map { it.asJsonObject.get("index").asInt.toString() }.toSet()
            for (e in JsonParser.parseString(run("pactl", "-f", "json", "list", "sink-inputs")).asJsonArray) {
                val o = e.asJsonObject
                val index = o.get("index").asInt
                if (index in saved || o.get("client")?.takeIf { !it.isJsonNull }?.asString in ours) continue
                val props = o.getAsJsonObject("properties")
                fun prop(k: String) = props?.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
                if (prop("media.role").lowercase(Locale.ROOT) in setOf("phone", "communication")) continue
                if (CALL_APPS.containsMatchIn(prop("application.process.binary") + " " + prop("application.name"))) continue
                val volumes = o.getAsJsonObject("volume")?.entrySet()?.map { it.value.asJsonObject.get("value").asLong } ?: continue
                if (volumes.isEmpty()) continue
                saved[index] = volumes to o.get("mute").asBoolean
                if (mute) run("pactl", "set-sink-input-mute", "$index", "1")
                else run("pactl", "set-sink-input-volume", "$index", *volumes.map { Math.round(it * factor).toString() }.toTypedArray())
            }
        }

        @Synchronized
        override fun restore() {
            for ((index, was) in saved) {
                run("pactl", "set-sink-input-volume", "$index", *was.first.map { it.toString() }.toTypedArray())
                run("pactl", "set-sink-input-mute", "$index", if (was.second) "1" else "0")
            }
            saved.clear()
        }
    }

    /** Windows: each audio session's own volume in the mixer, through a PowerShell helper (Core Audio over COM). */
    private object WindowsBackend : Backend {
        override val refreshTicks = 300
        /** Session instance id -> its "volume<TAB>mute" before it was turned down. */
        private val saved = LinkedHashMap<String, String>()

        private val script: String by lazy {
            val f = java.nio.file.Files.createTempFile("coffeeclient-quiet-media", ".ps1")
            f.toFile().deleteOnExit()
            java.nio.file.Files.writeString(f, SCRIPT)
            f.toString()
        }

        private fun ps(vararg args: String, input: String) =
            run("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script, *args, input = input)

        @Synchronized
        override fun quiet(factor: Double, mute: Boolean) {
            val out = ps("quiet", String.format(Locale.ROOT, "%.3f", factor), if (mute) "1" else "0", ownPid, input = saved.keys.joinToString("\n"))
            for (line in out.lines()) {
                val parts = line.trim().split('\t')
                if (parts.size == 3) saved[parts[0]] = parts[1] + "\t" + parts[2]
            }
        }

        @Synchronized
        override fun restore() {
            if (saved.isEmpty()) return
            ps("restore", input = saved.entries.joinToString("\n") { it.key + "\t" + it.value })
            saved.clear()
        }

        private val SCRIPT = """
param([string]${'$'}mode, [double]${'$'}factor = 1, [int]${'$'}mute = 0, [int]${'$'}self = 0)
${'$'}ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
namespace CoffeeQuietMedia {
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}
  [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator {
    int EnumAudioEndpoints();
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
  }
  [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
  }
  [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionManager2 {
    int GetAudioSessionControl();
    int GetSimpleAudioVolume();
    [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessions);
  }
  [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionEnumerator {
    [PreserveSig] int GetCount(out int count);
    [PreserveSig] int GetSession(int index, out IAudioSessionControl2 session);
  }
  [Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionControl2 {
    int GetState(); int GetDisplayName(); int SetDisplayName(); int GetIconPath(); int SetIconPath();
    int GetGroupingParam(); int SetGroupingParam(); int RegisterAudioSessionNotification(); int UnregisterAudioSessionNotification();
    [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetProcessId(out uint pid);
    [PreserveSig] int IsSystemSoundsSession();
  }
  [Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface ISimpleAudioVolume {
    [PreserveSig] int SetMasterVolume(float level, ref Guid context);
    [PreserveSig] int GetMasterVolume(out float level);
    [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
    [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
  }
  public class Session {
    public string Id; public uint Pid; public float Volume; public bool Mute; public bool System;
    internal ISimpleAudioVolume V;
  }
  public static class Api {
    public static List<Session> All() {
      var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
      IMMDevice dev; Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0, 1, out dev));
      Guid iid = typeof(IAudioSessionManager2).GUID; object o;
      Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o));
      IAudioSessionEnumerator se; Marshal.ThrowExceptionForHR(((IAudioSessionManager2)o).GetSessionEnumerator(out se));
      int n; se.GetCount(out n);
      var list = new List<Session>();
      for (int i = 0; i < n; i++) {
        IAudioSessionControl2 c; if (se.GetSession(i, out c) != 0 || c == null) continue;
        var s = new Session();
        if (c.GetSessionInstanceIdentifier(out s.Id) != 0 || s.Id == null) continue;
        c.GetProcessId(out s.Pid);
        s.System = c.IsSystemSoundsSession() == 0;
        s.V = (ISimpleAudioVolume)c;
        s.V.GetMasterVolume(out s.Volume); s.V.GetMute(out s.Mute);
        list.Add(s);
      }
      return list;
    }
    public static void Set(Session s, float volume, bool mute) {
      Guid g = Guid.Empty; s.V.SetMasterVolume(Math.Max(0f, Math.Min(1f, volume)), ref g); s.V.SetMute(mute, ref g);
    }
  }
}
'@
${'$'}inv = [Globalization.CultureInfo]::InvariantCulture
${'$'}lines = @([Console]::In.ReadToEnd() -split "`n" | ForEach-Object { ${'$'}_.Trim() } | Where-Object { ${'$'}_ })
${'$'}calls = '^(Discord|DiscordCanary|DiscordPTB|TeamSpeak|ts3client_win64|Teams|ms-teams|Zoom|Mumble|Skype)${'$'}'
if (${'$'}mode -eq 'quiet') {
  ${'$'}skip = @{}; foreach (${'$'}l in ${'$'}lines) { ${'$'}skip[${'$'}l] = 1 }
  foreach (${'$'}s in [CoffeeQuietMedia.Api]::All()) {
    if (${'$'}s.System -or ${'$'}s.Pid -eq 0 -or ${'$'}s.Pid -eq ${'$'}self -or ${'$'}skip.ContainsKey(${'$'}s.Id)) { continue }
    ${'$'}name = ''; try { ${'$'}name = (Get-Process -Id ${'$'}s.Pid).ProcessName } catch {}
    if (${'$'}name -match ${'$'}calls) { continue }
    [Console]::Out.WriteLine(${'$'}s.Id + "`t" + ${'$'}s.Volume.ToString(${'$'}inv) + "`t" + [int]${'$'}s.Mute)
    if (${'$'}mute -eq 1) { [CoffeeQuietMedia.Api]::Set(${'$'}s, ${'$'}s.Volume, ${'$'}true) }
    else { [CoffeeQuietMedia.Api]::Set(${'$'}s, [float](${'$'}s.Volume * ${'$'}factor), ${'$'}s.Mute) }
  }
} elseif (${'$'}mode -eq 'restore') {
  ${'$'}want = @{}; foreach (${'$'}l in ${'$'}lines) { ${'$'}p = ${'$'}l.Split("`t"); if (${'$'}p.Length -eq 3) { ${'$'}want[${'$'}p[0]] = ${'$'}p } }
  foreach (${'$'}s in [CoffeeQuietMedia.Api]::All()) {
    if (${'$'}want.ContainsKey(${'$'}s.Id)) { ${'$'}p = ${'$'}want[${'$'}s.Id]; [CoffeeQuietMedia.Api]::Set(${'$'}s, [float]::Parse(${'$'}p[1], ${'$'}inv), ${'$'}p[2] -eq '1') }
  }
}
""".trimStart()
    }
}
