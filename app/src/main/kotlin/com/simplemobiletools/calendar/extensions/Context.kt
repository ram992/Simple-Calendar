package com.simplemobiletools.calendar.extensions

import android.Manifest
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.support.v4.content.ContextCompat
import android.support.v7.app.NotificationCompat
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.activities.EventActivity
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.helpers.Formatter
import com.simplemobiletools.calendar.models.CalDAVCalendar
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.calendar.receivers.NotificationReceiver
import com.simplemobiletools.calendar.services.SnoozeService
import com.simplemobiletools.commons.extensions.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.text.SimpleDateFormat
import java.util.*

fun Context.hasCalendarPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

fun Context.updateWidgets() {
    val widgetsCnt = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, MyWidgetMonthlyProvider::class.java))
    if (widgetsCnt.isNotEmpty()) {
        val ids = intArrayOf(R.xml.widget_monthly_info)
        Intent(this, MyWidgetMonthlyProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(this)
        }
    }

    updateListWidget()
}

fun Context.updateListWidget() {
    val widgetsCnt = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, MyWidgetListProvider::class.java))
    if (widgetsCnt.isNotEmpty()) {
        val ids = intArrayOf(R.xml.widget_list_info)
        Intent(this, MyWidgetListProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(this)
        }
    }
}

fun Context.scheduleAllEvents() {
    val events = dbHelper.getEventsAtReboot()
    events.forEach {
        scheduleNextEventReminder(it, dbHelper)
    }
}

fun Context.scheduleNextEventReminder(event: Event, dbHelper: DBHelper) {
    if (event.getReminders().isEmpty())
        return

    val now = (System.currentTimeMillis() / 1000).toInt()
    val reminderSeconds = event.getReminders().reversed().map { it * 60 }

    dbHelper.getEvents(now, now + YEAR, event.id) {
        if (it.isNotEmpty()) {
            for (curEvent in it) {
                for (curReminder in reminderSeconds) {
                    if (curEvent.getEventStartTS() - curReminder > now) {
                        scheduleEventIn((curEvent.getEventStartTS() - curReminder) * 1000L, curEvent)
                        return@getEvents
                    }
                }
            }
        }
    }
}

fun Context.scheduleReminder(event: Event, dbHelper: DBHelper) {
    if (event.getReminders().isNotEmpty())
        scheduleNextEventReminder(event, dbHelper)
}

fun Context.scheduleEventIn(notifTS: Long, event: Event) {
    if (notifTS < System.currentTimeMillis())
        return

    val pendingIntent = getNotificationIntent(this, event)
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

    if (isKitkatPlus())
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, notifTS, pendingIntent)
    else
        alarmManager.set(AlarmManager.RTC_WAKEUP, notifTS, pendingIntent)
}

fun Context.cancelNotification(id: Int) {
    val intent = Intent(this, NotificationReceiver::class.java)
    PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT).cancel()
}

private fun getNotificationIntent(context: Context, event: Event): PendingIntent {
    val intent = Intent(context, NotificationReceiver::class.java)
    intent.putExtra(EVENT_ID, event.id)
    intent.putExtra(EVENT_OCCURRENCE_TS, event.startTS)
    return PendingIntent.getBroadcast(context, event.id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
}

fun Context.getAppropriateTheme() = if (config.backgroundColor.getContrastColor() == Color.WHITE) R.style.MyDialogTheme_Dark else R.style.MyDialogTheme

fun Context.getFormattedMinutes(minutes: Int, showBefore: Boolean = true) = when (minutes) {
    -1 -> getString(R.string.no_reminder)
    0 -> getString(R.string.at_start)
    else -> {
        if (minutes % 525600 == 0)
            resources.getQuantityString(R.plurals.years, minutes / 525600, minutes / 525600)
        if (minutes % 43200 == 0)
            resources.getQuantityString(R.plurals.months, minutes / 43200, minutes / 43200)
        else if (minutes % 10080 == 0)
            resources.getQuantityString(R.plurals.weeks, minutes / 10080, minutes / 10080)
        else if (minutes % 1440 == 0)
            resources.getQuantityString(R.plurals.days, minutes / 1440, minutes / 1440)
        else if (minutes % 60 == 0) {
            val base = if (showBefore) R.plurals.hours_before else R.plurals.by_hours
            resources.getQuantityString(base, minutes / 60, minutes / 60)
        } else {
            val base = if (showBefore) R.plurals.minutes_before else R.plurals.by_minutes
            resources.getQuantityString(base, minutes, minutes)
        }
    }
}

fun Context.getRepetitionText(seconds: Int): String {
    val days = seconds / 60 / 60 / 24
    return when (days) {
        0 -> getString(R.string.no_repetition)
        1 -> getString(R.string.daily)
        7 -> getString(R.string.weekly)
        30 -> getString(R.string.monthly)
        365 -> getString(R.string.yearly)
        else -> {
            if (days % 365 == 0)
                resources.getQuantityString(R.plurals.years, days / 365, days / 365)
            else if (days % 30 == 0)
                resources.getQuantityString(R.plurals.months, days / 30, days / 30)
            else if (days % 7 == 0)
                resources.getQuantityString(R.plurals.weeks, days / 7, days / 7)
            else
                resources.getQuantityString(R.plurals.days, days, days)
        }
    }
}

fun Context.getFilteredEvents(events: List<Event>): List<Event> {
    val displayEventTypes = config.displayEventTypes
    return events.filter { displayEventTypes.contains(it.eventType.toString()) }
}

fun Context.notifyRunningEvents() {
    dbHelper.getRunningEvents().forEach { notifyEvent(it) }
}

fun Context.notifyEvent(event: Event) {
    val pendingIntent = getPendingIntent(this, event)
    val startTime = Formatter.getTimeFromTS(this, event.startTS)
    val endTime = Formatter.getTimeFromTS(this, event.endTS)
    val timeRange = if (event.getIsAllDay()) getString(R.string.all_day) else getFormattedEventTime(startTime, endTime)
    val notification = getNotification(this, pendingIntent, event, "$timeRange ${event.description}")
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(event.id, notification)
}

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
private fun getNotification(context: Context, pendingIntent: PendingIntent, event: Event, content: String): Notification {
    val soundUri = Uri.parse(context.config.reminderSound)
    val builder = NotificationCompat.Builder(context)
            .setContentTitle(event.title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentIntent(pendingIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setAutoCancel(true)
            .setSound(soundUri)
            .addAction(R.drawable.ic_snooze, context.getString(R.string.snooze), getSnoozePendingIntent(context, event))

    if (context.isLollipopPlus())
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)

    if (context.config.vibrateOnReminder)
        builder.setVibrate(longArrayOf(0, 300, 300, 300))

    return builder.build()
}

private fun getFormattedEventTime(startTime: String, endTime: String) = if (startTime == endTime) startTime else "$startTime - $endTime"

private fun getPendingIntent(context: Context, event: Event): PendingIntent {
    val intent = Intent(context, EventActivity::class.java)
    intent.putExtra(EVENT_ID, event.id)
    intent.putExtra(EVENT_OCCURRENCE_TS, event.startTS)
    return PendingIntent.getActivity(context, event.id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
}

private fun getSnoozePendingIntent(context: Context, event: Event): PendingIntent {
    val intent = Intent(context, SnoozeService::class.java).setAction("snooze")
    intent.putExtra(EVENT_ID, event.id)
    intent.putExtra(EVENT_OCCURRENCE_TS, event.startTS)
    return PendingIntent.getService(context, event.id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
}

fun Context.launchNewEventIntent(startNewTask: Boolean = false, today: Boolean = false) {
    val code = Formatter.getDayCodeFromDateTime(DateTime(DateTimeZone.getDefault()).plusDays(if (today) 0 else 1))
    Intent(applicationContext, EventActivity::class.java).apply {
        putExtra(NEW_EVENT_START_TS, getNewEventTimestampFromCode(code))
        if (startNewTask)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(this)
    }
}

fun Context.getCalDAVCalendars(ids: String = ""): List<CalDAVCalendar> {
    val calendars = ArrayList<CalDAVCalendar>()
    if (!hasCalendarPermission()) {
        return calendars
    }

    dbHelper.fetchEventTypes()
    val uri = CalendarContract.Calendars.CONTENT_URI
    val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR)
    val selection = if (ids.trim().isNotEmpty()) "${CalendarContract.Calendars._ID} IN ($ids)" else null

    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, selection, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            do {
                val id = cursor.getLongValue(CalendarContract.Calendars._ID)
                val displayName = cursor.getStringValue(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountName = cursor.getStringValue(CalendarContract.Calendars.ACCOUNT_NAME)
                val ownerName = cursor.getStringValue(CalendarContract.Calendars.OWNER_ACCOUNT)
                val color = cursor.getIntValue(CalendarContract.Calendars.CALENDAR_COLOR)
                val calendar = CalDAVCalendar(id, displayName, accountName, ownerName, color)
                calendars.add(calendar)
            } while (cursor.moveToNext())
        }
    } finally {
        cursor?.close()
    }
    return calendars
}

fun Context.fetchCalDAVCalendarEvents(calendarId: Long, eventTypeId: Int) {
    val importIdsMap = HashMap<String, Event>()
    val existingEvents = dbHelper.getEventsFromCalDAVCalendar(calendarId)
    existingEvents.forEach {
        importIdsMap.put(it.importId, it)
    }

    val uri = CalendarContract.Events.CONTENT_URI
    val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE)
    val selection = "${CalendarContract.Events.CALENDAR_ID} = $calendarId"

    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, selection, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            do {
                val id = cursor.getLongValue(CalendarContract.Events._ID)
                val title = cursor.getStringValue(CalendarContract.Events.TITLE) ?: continue
                val description = cursor.getStringValue(CalendarContract.Events.DESCRIPTION)
                val startTS = (cursor.getLongValue(CalendarContract.Events.DTSTART) / 1000).toInt()
                var endTS = (cursor.getLongValue(CalendarContract.Events.DTEND) / 1000).toInt()
                val allDay = cursor.getIntValue(CalendarContract.Events.ALL_DAY)
                val rrule = cursor.getStringValue(CalendarContract.Events.RRULE) ?: ""
                val reminders = getCalDAVEventReminders(id)

                if (endTS == 0) {
                    val duration = cursor.getStringValue(CalendarContract.Events.DURATION)
                    endTS = startTS + Parser().parseDuration(duration)
                }

                val importId = getCalDAVEventImportId(calendarId, id)
                val repeatRule = Parser().parseRepeatInterval(rrule, startTS)
                val event = Event(0, startTS, endTS, title, description, reminders.getOrElse(0, { -1 }),
                        reminders.getOrElse(1, { -1 }), reminders.getOrElse(2, { -1 }), repeatRule.repeatInterval,
                        importId, allDay, repeatRule.repeatLimit, repeatRule.repeatRule, eventTypeId, source = "$CALDAV-$calendarId")

                if (event.getIsAllDay() && endTS > startTS) {
                    event.endTS -= DAY
                }

                if (importIdsMap.containsKey(event.importId)) {
                    val existingEvent = importIdsMap[importId]
                    val originalEventId = existingEvent!!.id
                    existingEvent.id = 0
                    if (existingEvent.hashCode() != event.hashCode()) {
                        event.id = originalEventId
                        dbHelper.update(event) {
                        }
                    }
                } else {
                    dbHelper.insert(event) {
                        importIdsMap.put(event.importId, event)
                    }
                }
            } while (cursor.moveToNext())
        }
    } finally {
        cursor?.close()
    }
}

fun Context.addCalDAVEvent(event: Event, calendarId: Long) {
    val durationMinutes = (event.endTS - event.startTS) / 1000 / 60
    val uri = CalendarContract.Events.CONTENT_URI
    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.Events.DESCRIPTION, event.description)
        put(CalendarContract.Events.DTSTART, event.startTS * 1000L)
        put(CalendarContract.Events.ALL_DAY, if (event.getIsAllDay()) 1 else 0)
        put(CalendarContract.Events.RRULE, Parser().getShortRepeatInterval(event))
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().toString())

        if (event.getIsAllDay() && event.endTS > event.startTS)
            event.endTS += DAY

        if (event.repeatInterval > 0) {
            put(CalendarContract.Events.DURATION, Parser().getDurationString(durationMinutes))
        } else {
            put(CalendarContract.Events.DTEND, event.endTS * 1000L)
        }
    }

    val newUri = contentResolver.insert(uri, values)
    val eventRemoteID = java.lang.Long.parseLong(newUri.lastPathSegment)

    val importId = getCalDAVEventImportId(calendarId, eventRemoteID)
    dbHelper.updateEventImportIdAndSource(event.id, importId, "$CALDAV-$calendarId")
}

fun Context.getCalDAVEventReminders(eventId: Long): List<Int> {
    val reminders = ArrayList<Int>()
    val uri = CalendarContract.Reminders.CONTENT_URI
    val projection = arrayOf(
            CalendarContract.Reminders.MINUTES,
            CalendarContract.Reminders.METHOD)
    val selection = "${CalendarContract.Reminders.EVENT_ID} = $eventId"
    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, selection, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            do {
                val minutes = cursor.getIntValue(CalendarContract.Reminders.MINUTES)
                val method = cursor.getIntValue(CalendarContract.Reminders.METHOD)
                if (method == CalendarContract.Reminders.METHOD_ALERT) {
                    reminders.add(minutes)
                }
            } while (cursor.moveToNext())
        }
    } finally {
        cursor?.close()
    }
    return reminders
}

fun Context.getCalDAVEventImportId(calendarId: Long, eventId: Long) = "$CALDAV-$calendarId-$eventId"

fun Context.getNewEventTimestampFromCode(dayCode: String) = Formatter.getLocalDateTimeFromCode(dayCode).withTime(13, 0, 0, 0).seconds()

fun Context.getCurrentOffset() = SimpleDateFormat("Z", Locale.getDefault()).format(Date())

val Context.config: Config get() = Config.newInstance(this)

val Context.dbHelper: DBHelper get() = DBHelper.newInstance(this)
