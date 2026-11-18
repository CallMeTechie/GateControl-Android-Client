package com.gatecontrol.android.familie.rules

import com.gatecontrol.android.places.PlaceIcons
import com.gatecontrol.android.places.PlaceOption

/**
 * Familie 1.30: the places a rule may wait at in the shared choice — Familie's rule places (`place:<id>`, blue) and the
 * places of „Meine Orte“ the family members shared (`profile:<id>`, with whose they are). The phone knows no point of
 * them (Familie watches its copies), so they are choosable without one. Pure, unit-tested.
 */
object RulePlaceChoices {
    private const val FAMILIE_PREFIX = "place:"

    fun options(o: RuleOptions): List<PlaceOption> {
        val familie = o.places.mapIndexed { i, p ->
            PlaceOption(ref = FAMILIE_PREFIX + p.id, name = p.name, icon = PlaceIcons.PIN, familie = true, rank = i, remote = true)
        }
        val shared = o.profilePlaces.orEmpty().mapIndexed { i, p ->
            PlaceOption(
                ref = p.ref,
                name = p.name,
                icon = p.icon,
                radiusM = p.radius,
                // whose it is, when it is not mine
                address = p.ownerName?.takeIf { !p.own },
                rank = familie.size + i,
                remote = true,
            )
        }
        return familie + shared
    }

    /** The trigger's place as a choice: its reference (Familie 1.30), else its rule place. */
    fun selected(t: RuleTrigger, options: List<PlaceOption>): PlaceOption? {
        val key = t.placeRef ?: t.placeId?.let { FAMILIE_PREFIX + it } ?: return null
        return options.firstOrNull { it.ref == key }
            ?: PlaceOption(ref = key, name = t.placeName ?: "#", familie = t.placeRef == null, remote = true)
    }

    /** The trigger with the chosen place: a rule place by its id, one of „Meine Orte“ by its reference. */
    fun apply(t: RuleTrigger, o: PlaceOption): RuleTrigger {
        val ref = o.ref ?: return t
        val familieId = ref.removePrefix(FAMILIE_PREFIX).takeIf { ref.startsWith(FAMILIE_PREFIX) }?.toIntOrNull()
        return if (familieId != null) {
            t.copy(placeId = familieId, placeRef = null, placeName = o.name, placeMissing = false)
        } else {
            t.copy(placeId = null, placeRef = ref, placeName = o.name, placeMissing = false)
        }
    }
}
