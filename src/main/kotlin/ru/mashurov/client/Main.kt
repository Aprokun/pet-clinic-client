package ru.mashurov.client

import Keyboard.Companion.listKeyboard
import Keyboard.Companion.mainMenuKeyboard
import Keyboard.Companion.petOneKeyboard
import Keyboard.Companion.petsKeyboard
import Keyboard.Companion.regionsKeyboard
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.github.kotlintelegrambot.logging.LogLevel
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.mashurov.client.IdType.TABLE
import ru.mashurov.client.IdType.TELEGRAM
import ru.mashurov.client.Messages.Companion.GENERAL_ERROR_MESSAGE
import ru.mashurov.client.Messages.Companion.startMessage
import ru.mashurov.client.Utils.Companion.convertAppointmentPlace
import ru.mashurov.client.Utils.Companion.convertDateToNormalFormat
import ru.mashurov.client.Utils.Companion.determineGender
import ru.mashurov.client.Utils.Companion.getDates
import ru.mashurov.client.Utils.Companion.getRusDayName
import ru.mashurov.client.Utils.Companion.getUrlParams
import ru.mashurov.client.Utils.Companion.isSameCallbackQueryDataUrl
import ru.mashurov.client.dtos.*
import ru.mashurov.client.services.*
import java.time.format.DateTimeFormatter.ofPattern

class Main

val gson: Gson = GsonBuilder()
    .setDateFormat("yyyy-MM-dd HH:mm:ssZ")
    .create()

val httpLoggingInterceptor = HttpLoggingInterceptor()

val okHttpClient = OkHttpClient.Builder().addInterceptor(httpLoggingInterceptor).build()

val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl("http://localhost:8080")
    .addConverterFactory(GsonConverterFactory.create(gson))
    .client(okHttpClient)
    .build()

val appointmentRequestCreateDto = AppointmentRequestCreateDto()

fun main() {

    httpLoggingInterceptor.level = HttpLoggingInterceptor.Level.BODY

    val userClient = retrofit.create(UserClient::class.java)
    val petClient = retrofit.create(PetClient::class.java)
    val appointmentRequestClient = retrofit.create(AppointmentRequestClient::class.java)
    val appointmentRequestsClient = retrofit.create(AppointmentRequestsClient::class.java)
    val appointmentClient = retrofit.create(AppointmentClient::class.java)
    val regionClient = retrofit.create(RegionClient::class.java)

    val bot = bot {
        token = System.getenv("BOT_TOKEN")
        logLevel = LogLevel.All()
        dispatch {
            startCommand(userClient)
            appointmentRequestsCommands(userClient, appointmentRequestsClient)
            petCommands(userClient, petClient)
            appointmentRequestCommands(userClient, appointmentRequestClient)
            mainCommand(userClient)
            profileSettingsCommands(regionClient, userClient)
        }
    }

    bot.startPolling()
}

private fun Dispatcher.appointmentRequestsCommands(
    userClient: UserClient,
    appointmentRequestsClient: AppointmentRequestsClient
) {

    callbackQuery("my_appointments") {

        val userRequest = userClient.getUser(callbackQuery.message!!.chat.id, TELEGRAM.type).execute()

        if (userRequest.isSuccessful) {

            val userId = userRequest.body()!!.id!!

            val requestsRequest = appointmentRequestsClient.findAllByUserId(userId).execute()

            if (requestsRequest.isSuccessful) {

                val requests = requestsRequest.body()!!

                if (requests.content.isNotEmpty()) {

                    requests.content.forEach {

                        val keyboard = InlineKeyboardMarkup.create(
                            listOf(
                                listOf(
                                    InlineKeyboardButton.CallbackData("????????????????", "req_cancel?id=${it.id}")
                                )
                            )
                        )

                        bot.sendMessage(
                            ChatId.fromId(callbackQuery.message!!.chat.id),
                            """
                            ?????????????????? ???${it.id}
                            ??????????????: ${it.clinicName}
                            ??????????: ${if (it.appointmentPlace == "clinic") it.clinicAddress else "???? ????????"}
                            ??????????????????: ${it.veterinarianName}
                            ????????????: ${it.serviceName}
                            ????????: ${convertDateToNormalFormat(it)}
                            ???????????? ??????????????????: ${it.status}
                            ??????????????: ${it.petName}
                        """.trimIndent(),
                            replyMarkup = if (it.status == "??????????????" || it.status == "??????????????????") null else keyboard
                        )
                    }

                } else {

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        callbackQuery.message!!.messageId,
                        text = "?? ?????? ?????? ?????????????? ???????????????????????????? ??????????????????",
                        replyMarkup = mainMenuKeyboard
                    )
                }

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }
    }

    callbackQuery("req_cancel") {

        val reqId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

        val userRequest = userClient.getUser(callbackQuery.message!!.chat.id, TELEGRAM.type).execute()

        if (userRequest.isSuccessful) {

            val userId = userRequest.body()!!.id!!

            val cancelRequest = appointmentRequestsClient.cancelById(userId, reqId).execute()

            if (cancelRequest.isSuccessful) {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "?????????????????? ???$reqId ????????????????"
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }
    }
}

private fun Dispatcher.profileSettingsCommands(
    regionClient: RegionClient,
    userClient: UserClient
) {

    callbackQuery("profile_settings") {

        bot.editMessageText(
            ChatId.fromId(callbackQuery.message!!.chat.id),
            callbackQuery.message!!.messageId,
            text = "?????????????????? ??????????????",
            replyMarkup = InlineKeyboardMarkup.create(
                listOf(listOf(InlineKeyboardButton.CallbackData("?????????????? ????????????", "change_reg")))
            )
        )
    }

    callbackQuery("change_reg") {

        val regionsRequest = regionClient.findAll().execute()

        if (regionsRequest.isSuccessful) {

            val regions = regionsRequest.body()!!

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = "???????????????? ?????????? ????????????",
                replyMarkup = listKeyboard(
                    "select_reg",
                    regions.map(RegionDto::toNamedEntityDto).toMutableList()
                )
            )

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }
    }

    callbackQuery("select_reg") {

        val regionCode = getUrlParams(callbackQuery.data)["id"]!!.toLong()

        val userRequest = userClient.getUser(callbackQuery.message!!.chat.id, TELEGRAM.type).execute()

        if (userRequest.isSuccessful) {

            val user = userRequest.body()!!

            val setRegionRequest = userClient.setRegion(user.id!!, regionCode).execute()

            if (setRegionRequest.isSuccessful) {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "?????????? ???????????? ?????????????? ????????????????????!",
                    replyMarkup = mainMenuKeyboard
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }

    }
}

private fun Dispatcher.appointmentRequestCommands(
    userClient: UserClient,
    appointmentRequestClient: AppointmentRequestClient
) {

    callbackQuery("appointment_req") {

        if (isSameCallbackQueryDataUrl("appointment_req", callbackQuery.data)) {

            val userRequest = userClient.getUser(callbackQuery.message!!.chat.id, TELEGRAM.type).execute()

            if (userRequest.isSuccessful && userRequest.body() != null) {

                val user = userRequest.body()!!

                appointmentRequestCreateDto.userId = user.id!!

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "???????????????? ??????????????",
                    replyMarkup = listKeyboard(
                        "appointment_req_1",
                        user.pets.map(PetDto::toNamedEntityDto).toMutableList()
                    )
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_1") {

        if (isSameCallbackQueryDataUrl("appointment_req_1", callbackQuery.data)) {

            appointmentRequestCreateDto.petId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            val user = userClient.getUser(appointmentRequestCreateDto.userId, TABLE.type).execute().body()!!
            val clinicsRequest = appointmentRequestClient.findAllClinics(user.region!!.code).execute()

            if (clinicsRequest.isSuccessful) {

                val clinics = clinicsRequest.body()!!.content

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "???????????????? ??????????????",
                    replyMarkup = listKeyboard(
                        "appointment_req_1_conf",
                        clinics.map(ClinicDto::toNamedEntityDto).toMutableList()
                    )
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }


    callbackQuery("appointment_req_1_conf") {

        if (isSameCallbackQueryDataUrl("appointment_req_1_conf", callbackQuery.data)) {

            appointmentRequestCreateDto.clinicId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            val clinicRequest = appointmentRequestClient.findClinic(appointmentRequestCreateDto.clinicId).execute()

            if (clinicRequest.isSuccessful) {

                val clinic = clinicRequest.body()!!

                val confirmationKeyboard = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(
                            InlineKeyboardButton.CallbackData("??????????", "appointment_req_2?id=${clinic.id}"),
                            InlineKeyboardButton.CallbackData(
                                "??????????",
                                "appointment_req_1?id=${appointmentRequestCreateDto.petId}"
                            )
                        )
                    )
                )

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = """
                    ???? ???????????? ?????????????? ???????????? ???????????????????????
                    ????????????????: ${clinic.name}
                    ??????????: ${clinic.address}
                    """.trimIndent(),
                    replyMarkup = confirmationKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_2") {

        if (isSameCallbackQueryDataUrl("appointment_req_2", callbackQuery.data)) {

            appointmentRequestCreateDto.clinicId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            val appointmentPlaceKeyboard = InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData("???? ????????", "appointment_req_3?place=home"),
                        InlineKeyboardButton.CallbackData("?? ??????????????", "appointment_req_3?place=clinic")
                    )
                )
            )

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = "???? ???????? ?????? ?? ??????????????",
                replyMarkup = appointmentPlaceKeyboard
            )
        }
    }

    callbackQuery("appointment_req_3") {

        if (isSameCallbackQueryDataUrl("appointment_req_3", callbackQuery.data)) {

            appointmentRequestCreateDto.appointmentPlace = getUrlParams(callbackQuery.data)["place"]!!

            val clinicRequest = appointmentRequestClient.findClinic(appointmentRequestCreateDto.clinicId).execute()

            if (clinicRequest.isSuccessful) {

                val clinic = clinicRequest.body()!!

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "???????????????? ?????????????????????? ????????????",
                    replyMarkup = listKeyboard(
                        "appointment_req_3_conf",
                        clinic.services.map(ServiceDto::toNamedEntityDto).toMutableList()
                    )
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_3_conf") {

        if (isSameCallbackQueryDataUrl("appointment_req_3_conf", callbackQuery.data)) {

            appointmentRequestCreateDto.serviceId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            val serviceRequest = appointmentRequestClient.findService(appointmentRequestCreateDto.serviceId).execute()

            if (serviceRequest.isSuccessful) {

                val service = serviceRequest.body()!!

                val confirmationKeyboard = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(
                            InlineKeyboardButton.CallbackData("??????????", "appointment_req_4?id=${service.id}"),
                            InlineKeyboardButton.CallbackData(
                                "??????????",
                                "appointment_req_3?place=${appointmentRequestCreateDto.appointmentPlace}"
                            )
                        )
                    )
                )

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = """
                    ???? ???????????? ?????????????? ???????????? ?????????????
                    ????????????????: ${service.name}
                    ????????????????: ${service.description}
                    ??????????????????: ${service.cost}
                    """.trimIndent(),
                    replyMarkup = confirmationKeyboard
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_4") {

        if (isSameCallbackQueryDataUrl("appointment_req_4", callbackQuery.data)) {

            appointmentRequestCreateDto.serviceId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            val veterinariansRequest = appointmentRequestClient
                .findAllVeterinariansByClinicId(appointmentRequestCreateDto.clinicId)
                .execute()

            if (veterinariansRequest.isSuccessful) {

                val veterinarians = veterinariansRequest.body()!!.content

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "???????????????? ????????????????????",
                    replyMarkup = listKeyboard(
                        "appointment_req_4_conf",
                        veterinarians.map(VeterinarianDto::toNamedEntityDto).toMutableList()
                    )
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_4_conf") {

        if (isSameCallbackQueryDataUrl("appointment_req_4_conf", callbackQuery.data)) {

            appointmentRequestCreateDto.veterinarianId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            val veterinarianRequest =
                appointmentRequestClient.findVeterinarian(appointmentRequestCreateDto.veterinarianId).execute()

            if (veterinarianRequest.isSuccessful) {

                val veterinarian = veterinarianRequest.body()!!

                val confirmationKeyboard = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(
                            InlineKeyboardButton.CallbackData("??????????", "appointment_req_5?id=${veterinarian.id}"),
                            InlineKeyboardButton.CallbackData(
                                "??????????",
                                "appointment_req_4?id=${appointmentRequestCreateDto.serviceId}"
                            )
                        )
                    )
                )

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = """
                    ???? ???????????? ?????????????? ?????????????? ?????????????????????
                    ??????: ${veterinarian.getSNP()}
                    ???????? ???????????? (????????): ${veterinarian.experience}
                    """.trimIndent(),
                    replyMarkup = confirmationKeyboard
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_5") {

        if (isSameCallbackQueryDataUrl("appointment_req_5", callbackQuery.data)) {

            appointmentRequestCreateDto.veterinarianId = getUrlParams(callbackQuery.data)["id"]!!.toLong()

            // ???????????????????? ???? ?????? ???????????? ????????????
            val dates = getDates(2)

            val inlineTable = mutableListOf<List<InlineKeyboardButton.CallbackData>>()
            var row = mutableListOf<InlineKeyboardButton.CallbackData>()

            for ((k, date) in dates.withIndex()) {

                val formatDate = date.format(ofPattern("dd.MM.yyyy"))

                row.add(
                    InlineKeyboardButton.CallbackData(
                        "${getRusDayName(date.dayOfWeek)}, $formatDate", "appointment_req_6?date=${date}"
                    )
                )

                if (k % 2 == 0) {
                    inlineTable.add(row.toList())
                    row = mutableListOf()
                }
            }

            val keyboard = InlineKeyboardMarkup.create(inlineTable)

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = "???????????????? ???????? ??????????????????",
                replyMarkup = keyboard
            )
        }
    }

    callbackQuery("appointment_req_6") {

        if (isSameCallbackQueryDataUrl("appointment_req_6", callbackQuery.data)) {

            appointmentRequestCreateDto.date = getUrlParams(callbackQuery.data)["date"]!!

            val timesRequest = appointmentRequestClient
                .findAllowTimePeriodsByVeterinarianIdAndDate(
                    appointmentRequestCreateDto.veterinarianId, appointmentRequestCreateDto.date
                )
                .execute()

            if (timesRequest.isSuccessful) {

                val times = timesRequest.body()

                if (!times.isNullOrEmpty()) {

                    val inlineTable = mutableListOf<List<InlineKeyboardButton.CallbackData>>()
                    var row = mutableListOf<InlineKeyboardButton.CallbackData>()

                    for ((k, time) in times.withIndex()) {

                        row.add(
                            InlineKeyboardButton.CallbackData(
                                time.start.removeSuffix(":00"), "appointment_req_7?time=${time.start}"
                            )
                        )

                        if (k % 2 == 0 && k != 0) {
                            inlineTable.add(row.toList())
                            row = mutableListOf()
                        }
                    }

                    if (row.isNotEmpty()) inlineTable.add(row)

                    val keyboard = InlineKeyboardMarkup.create(inlineTable)

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        callbackQuery.message!!.messageId,
                        text = "???????????????? ?????????? ??????????????????",
                        replyMarkup = keyboard
                    )

                } else {

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        callbackQuery.message!!.messageId,
                        text = "?????? ???????????????????? ?????? ?????? ????????????????????",
                        replyMarkup = mainMenuKeyboard
                    )
                }

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = GENERAL_ERROR_MESSAGE,
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }

    callbackQuery("appointment_req_7") {

        if (isSameCallbackQueryDataUrl("appointment_req_7", callbackQuery.data)) {

            appointmentRequestCreateDto.date += "T" + getUrlParams(callbackQuery.data)["time"]!!

            val response = appointmentRequestClient.createRequest(appointmentRequestCreateDto).execute()

            val answerMessage = when (response.isSuccessful) {
                true -> "???????? ?????????????????? ???? ?????????? ?????????????? ??????????????!"
                else -> "?? ??????????????????, ?????? ???????????????? ???????????? ?????????????????? ?????????????????? ??????????-???? ????????????. ?????????????????? ?????????????? ??????????"
            }

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = answerMessage,
                replyMarkup = mainMenuKeyboard
            )
        }
    }
}

private fun Dispatcher.mainCommand(userClient: UserClient) {

    command("main") {

        bot.sendMessage(
            ChatId.fromId(message.chat.id), "???????? ????????", replyMarkup = mainMenuKeyboard
        )
    }

    callbackQuery("back") {

        bot.editMessageText(
            ChatId.fromId(callbackQuery.message!!.chat.id),
            callbackQuery.message!!.messageId,
            text = "???????? ????????",
            replyMarkup = mainMenuKeyboard
        )
    }
}

private fun Dispatcher.petCommands(userClient: UserClient, petClient: PetClient) {

    callbackQuery("pets") {

        bot.editMessageText(
            ChatId.fromId(callbackQuery.message!!.chat.id),
            callbackQuery.message!!.messageId,
            text = "???????? ????????????????",
            replyMarkup = petsKeyboard
        )
    }

    callbackQuery("pets_list") {

        val userRequest = userClient.getUser(callbackQuery.message!!.chat.id, TELEGRAM.type).execute()

        if (userRequest.isSuccessful) {

            val user = userRequest.body()!!

            if (!user.pets.isNullOrEmpty()) {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "???????? ??????????????",
                    replyMarkup = listKeyboard(
                        "pet_one",
                        user.pets.map(PetDto::toNamedEntityDto).toMutableList()
                    )
                )

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "?? ?????? ???????? ?????? ?????? ?????????????? ????????????????",
                    replyMarkup = petsKeyboard
                )
            }

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }
    }

    callbackQuery("pets_add") {

        val user = userClient.getUser(callbackQuery.message!!.chat.id, TELEGRAM.type).execute().body()!!

        bot.sendMessage(
            ChatId.fromId(callbackQuery.message!!.chat.id),
            "?????????????? ??????, ?????? (??/??) ?? ?????????????? ?????????????? ?????????? ???????????? (???????????? \"?????????? ?? 3\")"
        )

        message(Filter.Text) {

            with(update.message?.text.toString().trim().split(" ")) {
                val name = get(0)
                val gender = determineGender(get(1))
                val age = get(2).toInt()

                val response = petClient
                    .save(PetDto(name, age, user = user, gender = gender, appointments = ArrayList()))
                    .execute()

                if (response.isSuccessful) {

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        callbackQuery.message!!.messageId,
                        text = "???? ???????????????? ???????????? ??????????????! ($name)",
                        replyMarkup = petsKeyboard
                    )

                } else {

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        text = GENERAL_ERROR_MESSAGE,
                        replyMarkup = petsKeyboard
                    )
                }
            }

        }
    }

    callbackQuery("pet_one") {

        val id = getUrlParams(callbackQuery.data)["id"]!!.toLong()
        val petRequest = petClient.get(id).execute()

        if (petRequest.isSuccessful) {

            val pet = petRequest.body()!!

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = """
                    ??????: ${pet.name}
                    ??????????????: ${pet.age}
                    ??????: ${pet.gender}
                """.trimIndent(),
                replyMarkup = petOneKeyboard(id)
            )

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }
    }

    callbackQuery("pet_one_delete") {

        val id = getUrlParams(callbackQuery.data)["id"]!!.toLong()
        val response = petClient.delete(id).execute()

        val answerMessage = when (response.isSuccessful) {
            true -> "?????????????? ?????????????? ????????????!"
            else -> "??????-???? ?????????? ???? ??????, ???????????????????? ??????????"
        }

        bot.editMessageText(
            ChatId.fromId(callbackQuery.message!!.chat.id),
            callbackQuery.message!!.messageId,
            text = answerMessage,
            replyMarkup = petsKeyboard
        )
    }

    callbackQuery("pet_one_change") {

        val id = getUrlParams(callbackQuery.data)["id"]!!.toLong()

        bot.sendMessage(
            ChatId.fromId(callbackQuery.message!!.chat.id),
            "?????????????? ?????????? ???????????? ?????? ?????????????? (??????, ??????, ?????????????? ?????????? ????????????)"
        )

        message(Filter.Text) {

            with(update.message?.text.toString().trim().split(" ")) {

                val name = get(0)
                val gender = determineGender(get(1))
                val age = get(2).toInt()

                val updateDto = PetUpdateDto(id, name, gender, age)

                val petUpdateRequest = petClient.update(updateDto).execute()

                if (petUpdateRequest.isSuccessful) {

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        callbackQuery.message!!.messageId,
                        text = "?????????????? ???????????????? ($name)",
                        replyMarkup = petsKeyboard
                    )

                } else {

                    bot.editMessageText(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        text = GENERAL_ERROR_MESSAGE,
                        replyMarkup = petsKeyboard
                    )
                }
            }
            //TODO ?????????????? ???? ??????, ?? ???? ???????? ????????????????
        }
    }

    callbackQuery("pet_one_disease") {

        val id = getUrlParams(callbackQuery.data)["id"]!!.toLong()
        val petRequest = petClient.getAppointmentHistory(id).execute()

        if (petRequest.isSuccessful) {

            val appointments = petRequest.body()

            if (!appointments.isNullOrEmpty()) {

                appointments.forEach {
                    bot.sendMessage(
                        ChatId.fromId(callbackQuery.message!!.chat.id),
                        """
                            ?????????????? ??????????????????: ${it.serviceName},
                            ???????? ??????????????????: ${convertDateToNormalFormat(it)}
                            ?????????? ??????????????????: ${convertAppointmentPlace(it.appointmentPlace)}
                            ????????: ${it.veterinarianName}
                        """.trimIndent()
                    )
                }

            } else {

                bot.editMessageText(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    callbackQuery.message!!.messageId,
                    text = "?? ?????????????? ???? ???????? ?????????????????? ????????????????????",
                    replyMarkup = petOneKeyboard(id)
                )
            }

        } else {

            bot.editMessageText(
                ChatId.fromId(callbackQuery.message!!.chat.id),
                callbackQuery.message!!.messageId,
                text = GENERAL_ERROR_MESSAGE,
                replyMarkup = mainMenuKeyboard
            )
        }
    }
}

private fun Dispatcher.startCommand(userClient: UserClient) {

    command("start") {

        val isUserExists = userClient.existByTelegramId(message.chat.id, TELEGRAM.type).execute().body()!!

        if (!isUserExists) {

            bot.sendMessage(ChatId.fromId(message.chat.id), startMessage)

            val regionsRequest = userClient.getRegions().execute()

            if (regionsRequest.isSuccessful) {

                val regions = regionsRequest.body()!!

                val namedEntityRegions = regions
                    .map { region -> NamedEntityDto(region.code, region.name) }
                    .toMutableList()

                bot.sendMessage(
                    ChatId.fromId(message.chat.id),
                    "???????????????? ?????? ????????????",
                    replyMarkup = regionsKeyboard("regions", namedEntityRegions)
                )
            }

        } else {

            bot.sendMessage(
                ChatId.fromId(message.chat.id),
                "????????????????????????, ${message.chat.firstName}! ?????????? ?????????????? ?? ???????????????? ???????? ???????????????????????????? ???????????????? /main. " +
                        "???????? ?? ?????? ???????????????? ??????????-???? ????????????????, ???? ???????????????????????????? ???????????????? /help"
            )
        }
    }

    callbackQuery("regions") {

        val regionCode = getUrlParams(callbackQuery.data)["id"]!!.toLong()

        val regionRequest = userClient.getRegion(regionCode).execute()

        if (regionRequest.isSuccessful) {

            val region = regionRequest.body()!!

            val userDto = UserDto(
                callbackQuery.message!!.chat.firstName!!,
                callbackQuery.message!!.chat.username,
                callbackQuery.message!!.chat.id,
                region
            )

            val userSaveRequest = userClient.save(userDto).execute()

            if (userSaveRequest.isSuccessful) {

                bot.sendMessage(
                    ChatId.fromId(callbackQuery.message!!.chat.id),
                    "???? ?????????????? ????????????????????????????!",
                    replyMarkup = mainMenuKeyboard
                )
            }
        }
    }
}
