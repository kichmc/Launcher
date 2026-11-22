package com.kich.launcher.data

/** Ids shared by every module that talks to the launcher's single AppWidgetHost. */
object WidgetHostIds {
    /** App-unique id for our single AppWidgetHost ("ARK1"). Must never change: widgets are bound to it. */
    const val APPWIDGET_HOST_ID = 0x41524B31
}
