package com.blushdesk.app.utils

/**
 * The name people see: top bar, welcome message, receipt footer, the Excel file's author, and the
 * names of exported files and the Downloads folder. The launcher label is `app_name` in
 * res/values/strings.xml; keep the two the same.
 *
 * The code keeps the original working name in its package and application id
 * (com.blushdesk.app). Changing the application id would make Android treat the app as a new one,
 * so copies already in use could not be updated without losing their records.
 */
const val APP_NAME = "FergBentables"
