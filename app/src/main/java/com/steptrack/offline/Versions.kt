package com.steptrack.offline

data class VersionInfo(val id: String, val title: String, val notes: List<String>)

/** Newest first. Shown in More > Settings > Version history. */
val VERSION_HISTORY = listOf(
    VersionInfo("V17", "Step accuracy, battery and places", listOf(
        "Steps: counter and per-step detector are reconciled on running totals with a 15 s grace period, so no step is counted twice",
        "Compass runs at 5 Hz and only while the app is open or you are walking (was 50 readings per second all day)",
        "GPS switches fully off after 5 min still; steps, significant motion or opening the app wake it",
        "Stationary lock no longer flaps on trains: 100 m movement in 30 s keeps it off; log spam removed, log keeps about 3x more days",
        "Places: default radius 75 m, trip start/end labelled with the nearest place within 150 m, frequent unnamed stops suggested for naming",
        "Compass: offline magnetic declination applied and a small offset learned from GPS course",
        "Altitude: 10 m hysteresis with median filter, poor vertical accuracy ignored; diagnostic climb totals per day",
        "New movement type Train (vehicle run between places marked Station or named Bf / Bahnhof / Hbf / Station)",
        "More: battery impact card (drain per hour by GPS mode); diagnostic report adds battery, step reconciliation and place-match checks")),
    VersionInfo("V16", "Date picker and distances on the circles", listOf(
        "Walk, Move and Height share one date bar: previous / next day buttons and a calendar",
        "Past days: path, replay, zoom, stats and trips all show the chosen day",
        "Distances written on the path circles (half ring and outer ring) and a distance caption under the circle")),
    VersionInfo("V15", "Accuracy and battery fixes from the diagnostic log", listOf(
        "Steps: each 10-second sensor batch is spread over its interval with real per-step times and headings; wake-up step detector added",
        "Steps: accelerometer gait detector switches on only when GPS shows walking but the step sensors are silent",
        "Step count and screens now reset at midnight on their own",
        "Walking is recognised from steady slow speed even when the step sensor missed steps (no more 'Slow vehicle / bike' for walks)",
        "Stationary lock: no steps for 45 s freezes the position, so indoor GPS wander is not recorded; trips under 100 m are hidden",
        "Battery: GPS drops to 10-second fixes when you are still, wake lock is timed (2 min) instead of permanent",
        "Direction uses GPS course while moving faster than 1.5 m/s, compass otherwise",
        "Height: GPS-only altitude smoothed more and climb totals ignore changes under 6 m (this phone has no barometer)",
        "Settings: Measure stride from GPS and Use it with one tap",
        "Move: grey now explained in the legend (Still / GPS wander)",
        "Places: Save now falls back to the last known position, tells you exactly why it fails, and every attempt is written to the app log")),
    VersionInfo("V14", "Fixed circle position", listOf(
        "Move and Walk: the line above the path circle is now always one line, so the circle no longer jumps up and down")),
    VersionInfo("V13", "Walk tab, replay speed, ground-level height", listOf(
        "Today tab renamed Walk; path circle now has the compass ring and needle (separate compass kept below)",
        "Path circle moved above the step count; goal circle replaced by a goal line",
        "Move tab: path circle, zoom, time slider and legend moved to the top; added Replay path button",
        "One replay speed setting (More > Settings) shared by Walk and Move replays",
        "Height tab: 3D path and sliders moved to the top; altitude is labelled as above sea level",
        "Height tab: 'Set ground level here' shows your height and floor above your own ground level",
        "GPS altitude now uses height above sea level (Android 14+) instead of height above the GPS ellipsoid, which reads about 45 m too high in Bavaria",
        "Version history added to the app")),
    VersionInfo("V12", "Build fix", listOf("Fixed the diagnostic report build error (sensor maximumRange)")),
    VersionInfo("V11", "Diagnostics and app log", listOf(
        "Private rolling app log with one-minute heartbeat (battery, sensors, GPS, steps)",
        "Diagnostic package ZIP: report, full log, steps CSV, movement CSV, KML, JSON",
        "Report covers device, permissions, sensors, data quality, GPS quality, stride and compass calibration, backup health",
        "Add-a-note button, clear log, log mirror in Documents/StepTrack/logs")),
    VersionInfo("V10", "Lab merged into Today", listOf(
        "Lab tab removed; direction breakdown, distance stats and path replay moved to Today")),
    VersionInfo("V9", "Named places and KML", listOf(
        "My places: name a location, trips shown as Home -> Office, place column in exports, places backed up",
        "KML path export for Google Maps and Earth")),
    VersionInfo("V3-V8", "Move and Height tabs, auto-save (intermediate builds)", listOf(
        "Move tab: GPS tracking, movement types, trips, speed chart",
        "Height tab: barometer + GPS elevation, height chart and 3D path",
        "Daily auto-save to Documents/StepTrack with restore after reinstall")),
    VersionInfo("V2", "GitHub build and fixed update key", listOf(
        "GitHub Actions workflow builds a signed APK",
        "Fixed signing key so updates install over the old app without data loss")),
    VersionInfo("V1", "First build", listOf(
        "Step counting with sensor fallbacks, compass heading, direction buckets, Today, Analytics, Lab and More screens",
        "CSV, JSON and PDF export"))
)
