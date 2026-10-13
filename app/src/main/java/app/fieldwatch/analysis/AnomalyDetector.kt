package app.fieldwatch.analysis

/**
 * Watches devices that do not belong to a bucket's baseline and promotes them
 * to anomalies only after they stay on the air for [persistMs] with a strong
 * enough signal. Pure state machine, unit-tested without Android.
 *
 * An anomaly that leaves the air resets and can promote again later; the
 * engine additionally dedups alerts against the database, so each
 * (device, type, place) alarms once ever.
 */
class AnomalyDetector(
    private val persistMs: Long = 120_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val firstHeard = HashMap<String, Long>()
    private val promoted = HashSet<String>()

    /**
     * Feed the anomalous deviceKeys currently heard in one bucket.
     * Returns the keys that crossed the persistence threshold this call.
     */
    fun observe(bucketKey: String, anomalousKeys: Set<String>): Set<String> {
        val now = now()
        val live = HashSet<String>()
        val fired = HashSet<String>()
        for (key in anomalousKeys) {
            val id = "$bucketKey|$key"
            live += id
            val since = firstHeard.getOrPut(id) { now }
            if (now - since >= persistMs && promoted.add(id)) {
                fired += key
            }
        }
        // Devices gone from the air reset their candidacy.
        val it = firstHeard.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (id.startsWith("$bucketKey|") && id !in live) {
                it.remove()
                promoted.remove(id)
            }
        }
        return fired
    }
}
