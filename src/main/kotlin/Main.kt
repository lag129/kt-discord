package org.example

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

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

    val apiClient = ApiClient()
    runBlocking {
        launch {
            val response = apiClient.audioQuery(1, "こんにちは")
            println(response.body()?.string())
            val response2 = apiClient.synthesis(1, response.body()?.string() ?: "")
            println(response2.body()?.string())
        }
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

interface ApiService {
    @POST("/audio_query")
    suspend fun audioQuery(
        @Query("speaker") speaker: Int,
        @Body text: String
    ): Response<ResponseBody>

    @POST("/synthesis")
    suspend fun synthesis(
        @Query("speaker") speaker: Int,
        @Body query: String
    ): Response<ResponseBody>
}

class ApiClient {
    companion object {
        private const val BASE_URL = "http://localhost:50031"
    }
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    private val apiService = retrofit.create(ApiService::class.java)

    suspend fun audioQuery(speaker: Int, text: String): Response<ResponseBody> {
        return apiService.audioQuery(speaker, text)
    }

    suspend fun synthesis(speaker: Int, query: String): Response<ResponseBody> {
        return apiService.synthesis(speaker, query)
    }
}