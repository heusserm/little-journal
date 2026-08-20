package com.xndev.littlejournal.app

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun Month.title(): String = MONTHS[ordinal]

fun Month.short(): String = title().take(3)

fun DayOfWeek.short(): String = WEEKDAYS[ordinal]

/** "Tue, 12 Aug 2026" */
fun LocalDate.longLabel(): String =
    "${dayOfWeek.short()}, $dayOfMonth ${month.short()} $year"

/** "August 2026" */
fun LocalDate.monthLabel(): String = "${month.title()} $year"

/** Weekday headers, Monday first. */
val weekdayHeaders: List<String> get() = WEEKDAYS.map { it.take(1) }
