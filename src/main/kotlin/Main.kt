package org.example

import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent

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
        guild.updateCommands().addCommands(commands).queue()

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class MyListener : ListenerAdapter() {
    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "join" -> handleJoin(event)
            "leave" -> handleLeave(event)
            "help" -> handleHelp(event)
        }
    }

    private fun handleJoin(event: SlashCommandInteractionEvent) {
        checkUserInVoiceChannel(event)
        val audioManager = event.guild?.audioManager
        val voiceChannel = event.member?.voiceState?.channel
        try {
            audioManager?.openAudioConnection(voiceChannel)
            event.reply("ボイスチャンネル「${voiceChannel?.name}」に接続しました！").setEphemeral(true).queue()
        } catch (e: Exception) {
            event.reply("❌ボイスチャンネルへの接続に失敗しました。").setEphemeral(true).queue()
            e.printStackTrace()
        }
    }

    private fun handleLeave(event: SlashCommandInteractionEvent) {
        checkUserInVoiceChannel(event)
        val audioManager = event.guild?.audioManager
        audioManager?.closeAudioConnection()
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