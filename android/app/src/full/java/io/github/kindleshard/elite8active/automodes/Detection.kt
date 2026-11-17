package io.github.kindleshard.elite8active.automodes

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Activity recognition and geofences through Google Play services (the "full" build). The
 * "fdroid" build has its own Detection with only Android's own location service.
 */
object Detection {
    /** Whether this build can tell what you're doing (walking, in a vehicle…). */
    const val CAN_DETECT_MOTION = true

    private val TYPES = mapOf(
        Motion.STILL to DetectedActivity.STILL,
        Motion.WALKING to DetectedActivity.WALKING,
        Motion.RUNNING to DetectedActivity.RUNNING,
        Motion.VEHICLE to DetectedActivity.IN_VEHICLE,
    )

    private fun motionIntent(context: Context) = AutoModes.pendingIntent(context, AutoModes.ACTION_MOTION)
    private fun placeIntent(context: Context) = AutoModes.pendingIntent(context, AutoModes.ACTION_PLACE)

    // Throws SecurityException without the activity permission; AutoModes.register catches it.
    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(motionIntent(context))
        LocationServices.getGeofencingClient(context).removeGeofences(placeIntent(context))
    }

    @SuppressLint("MissingPermission")
    fun watchMotion(context: Context) {
        val transitions = TYPES.values.map {
            ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        ActivityRecognition.getClient(context).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), motionIntent(context))
    }

    @SuppressLint("MissingPermission")
    fun watchPlaces(context: Context, places: List<Place>) {
        val fences = places.map {
            Geofence.Builder()
                .setRequestId(it.id)
                .setCircularRegion(it.latitude, it.longitude, AutoModes.PLACE_RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofences(fences).build()
        LocationServices.getGeofencingClient(context).addGeofences(request, placeIntent(context))
    }

    /** What you've started doing, from an activity-recognition broadcast. */
    fun motion(intent: Intent): Motion? {
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents
            ?.lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER } ?: return null
        return TYPES.entries.firstOrNull { it.value == event.activityType }?.key
    }

    /** Whether you arrived (true) or left (false), and which places, from a geofence broadcast. */
    fun placeChange(intent: Intent): Pair<Boolean, Set<String>>? {
        val event = GeofencingEvent.fromIntent(intent)?.takeIf { !it.hasError() } ?: return null
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.toSet()
        return when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true to ids
            Geofence.GEOFENCE_TRANSITION_EXIT -> false to ids
            else -> null
        }
    }
}
