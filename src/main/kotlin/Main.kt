package org.example

import com.github.kitakkun.ktvox.api.KtVoxApi
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

fun main() {
    val discordToken = System.getenv("DISCORD_TOKEN")
    val guildId = System.getenv("GUILD_ID")

    try {
        val jda = JDABuilder.createDefault(discordToken)
            .addEventListeners(MyListener())
            .enableIntents(GatewayIntent.MESSAGE_CONTENT)
            .setActivity(Activity.playing("読み上げボット"))
            .build()
        jda.awaitReady()

        val guild = jda.getGuildById(guildId)!!

        val commands = listOf(
            Commands.slash("join", "読み上げボットをボイスチャンネルへ接続します。"),
            Commands.slash("leave", "読み上げボットをボイスチャンネルから切断します。"),
            Commands.slash("help", "ヘルプを表示します。")
        )
        jda.updateCommands().addCommands(commands).queue()

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class MyListener : ListenerAdapter() {
    private val ktVoxApi = KtVoxApi.initialize("http://localhost:50031")
    private val scope = CoroutineScope(Dispatchers.IO)
    private val playerManager: AudioPlayerManager = DefaultAudioPlayerManager()
    private val musicManagers = mutableMapOf<Long, GuildMusicManager>()
    private val outputDir = File("./output")
    private var joinedChannel = ""

    init {
        AudioSourceManagers.registerRemoteSources(playerManager)
        AudioSourceManagers.registerLocalSource(playerManager)
        outputDir.mkdirs()
    }

    private fun getGuildAudioPlayer(guild: Guild): GuildMusicManager {
        return musicManagers.computeIfAbsent(guild.idLong) { GuildMusicManager(playerManager) }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "join" -> handleJoin(event)
            "leave" -> handleLeave(event)
            "help" -> handleHelp(event)
        }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        if (event.message.contentRaw.startsWith("!join")) {
            handleJoin(event)
            joinedChannel = event.message.channelId
        }

        if (joinedChannel.isEmpty()) return
        if (joinedChannel != event.message.channelId) return

        event.member?.voiceState?.channel ?: return

        val guildAudioPlayer = getGuildAudioPlayer(event.guild)

        scope.launch {
            try {
                val audioQuery = ktVoxApi.createAudioQuery(
                    text = event.message.contentRaw,
                    speaker = 0,
                )
                val generatedAudio = ktVoxApi.postSynthesis(
                    speaker = 0,
                    audioQuery = audioQuery,
                )

                val outputFile = File(outputDir, "${System.currentTimeMillis()}.wav")
                outputFile.writeBytes(generatedAudio)

                playerManager.loadItem(outputFile.absolutePath, object: AudioLoadResultHandler {
                    override fun trackLoaded(track: AudioTrack) {
                        guildAudioPlayer.scheduler.queue(track)
                    }

                    override fun playlistLoaded(playlist: AudioPlaylist) {
                        playlist.tracks.forEach { guildAudioPlayer.scheduler.queue(it) }
                    }

                    override fun noMatches() {
                        event.channel.sendMessage("❌再生する音声が見つかりませんでした。").queue()
                    }

                    override fun loadFailed(exception: FriendlyException) {
                        event.channel.sendMessage("❌音声の読み込みに失敗しました。").queue()
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleJoin(event: SlashCommandInteractionEvent) {
        checkUserInVoiceChannel(event)
        val audioManager = event.guild?.audioManager
        val voiceChannel = event.member?.voiceState?.channel

        try {
            val guildAudioPlayer = event.guild?.let { getGuildAudioPlayer(it) }
            audioManager?.sendingHandler = guildAudioPlayer?.getSendHandler()
            audioManager?.openAudioConnection(voiceChannel)
            event.reply("ボイスチャンネル「${voiceChannel?.name}」に接続しました！").setEphemeral(true).queue()

        } catch (e: Exception) {
            event.reply("❌ボイスチャンネルへの接続に失敗しました。").setEphemeral(true).queue()
            e.printStackTrace()
        }
    }

    private fun handleJoin(event: MessageReceivedEvent) {
//        checkUserInVoiceChannel(event)
        val audioManager = event.guild.audioManager
        val voiceChannel = event.member?.voiceState?.channel

        try {
            val guildAudioPlayer = getGuildAudioPlayer(event.guild)
            audioManager.sendingHandler = guildAudioPlayer.getSendHandler()
            audioManager.openAudioConnection(voiceChannel)
//            event.reply("ボイスチャンネル「${voiceChannel?.name}」に接続しました！").setEphemeral(true).queue()

        } catch (e: Exception) {
//            event.reply("❌ボイスチャンネルへの接続に失敗しました。").setEphemeral(true).queue()
            e.printStackTrace()
        }
    }

    private fun handleLeave(event: SlashCommandInteractionEvent) {
        checkUserInVoiceChannel(event)
        val audioManager = event.guild?.audioManager
        audioManager?.closeAudioConnection()
        joinedChannel = ""
        val dir = File("./output")
        dir.listFiles()?.forEach { it.delete() }
        event.reply("ボイスチャンネルから切断しました！").setEphemeral(true).queue()
    }

    private fun handleHelp(event: SlashCommandInteractionEvent) {
        event.reply("ヘルプを表示します。").setEphemeral(true).queue()
    }

    private fun checkUserInVoiceChannel(event: SlashCommandInteractionEvent) {
        event.member?.voiceState?.channel ?: run {
            event.reply("❌️ボイスチャンネルに参加していません。").setEphemeral(true).queue()
            throw Exception("ボイスチャンネルに参加していません。")
        }
    }
}

class GuildMusicManager(manager: AudioPlayerManager) {
    private val player: AudioPlayer = manager.createPlayer()
    val scheduler = TrackScheduler(player)

    init {
        player.addListener(scheduler)
        player.setFrameBufferDuration(500) // バッファ時間を増やす
        player.volume = 30
    }

    fun getSendHandler(): AudioSendHandler = AudioPlayerSendHandler(player)
}

class TrackScheduler(private val player: AudioPlayer) : AudioEventListener {
    private val queue = LinkedBlockingQueue<AudioTrack>()

    fun queue(track: AudioTrack) {
        if (!player.startTrack(track, true)) {
            queue.offer(track)
        }
    }

    private fun nextTrack() {
        player.startTrack(queue.poll(), false)
    }

    override fun onEvent(event: AudioEvent) {
        if (event is TrackEndEvent) {
            onTrackEnd(event.player, event.track, event.endReason)
        }
    }

    private fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        if (endReason.mayStartNext) {
            nextTrack()
        }
    }
}

class AudioPlayerSendHandler(private val audioPlayer: AudioPlayer) : AudioSendHandler {
    private var lastFrame: AudioFrame? = null

    override fun canProvide(): Boolean {
        lastFrame = audioPlayer.provide()
        return lastFrame != null
    }

    override fun provide20MsAudio(): ByteBuffer? {
        val data = lastFrame?.data ?: return null
        return ByteBuffer.wrap(data)
    }

    override fun isOpus(): Boolean {
        return true
    }
}