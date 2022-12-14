package ru.mashurov.client.dtos

data class AppointmentRequestCreateDto(
    var appointmentPlace: String = "",
    var clinicId: Long = 0,
    var serviceId: Long = 0,
    var veterinarianId: Long = 0,
    var userId: Long = 0,
    var date: String = "",
    var petId: Long = 0
)