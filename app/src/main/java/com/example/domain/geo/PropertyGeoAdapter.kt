package com.example.domain.geo

import com.example.data.local.entity.PropertyEntity

/**
 * Adapter between the persisted property rows and the SDK independent geo domain.
 *
 * It only reads existing columns - no database, scoring or enrichment behaviour is changed here.
 * Rows without a usable coordinate are dropped instead of being placed at a made-up location.
 */
object PropertyGeoAdapter {

    const val ATTR_SOURCE_TYPE = "sourceType"
    const val ATTR_STATUS = "status"
    const val ATTR_PROPERTY_TYPE = "propertyType"
    const val ATTR_CITY = "city"
    const val ATTR_STATE = "state"
    const val ATTR_ZIP = "zipCode"

    /** Converts a property row into a pin, or `null` when it has no usable coordinate. */
    fun toPin(
        property: PropertyEntity,
        role: MapPinRole = MapPinRole.PROPERTY,
        emphasis: MapPinEmphasis = defaultEmphasis(property, role)
    ): MapPin? {
        val point = GeoPoint.orNull(property.latitude, property.longitude) ?: return null
        return MapPin(
            id = property.id,
            point = point,
            role = role,
            label = property.address.ifBlank { property.title },
            subLabel = listOf(property.city, property.state)
                .filter { it.isNotBlank() }
                .joinToString(", "),
            priceUsd = property.price.takeIf { it.isFinite() && it > 0.0 },
            dealScore = property.dealScore.takeIf { it in 0..100 },
            emphasis = emphasis,
            attributes = buildMap {
                if (property.sourceType.isNotBlank()) put(ATTR_SOURCE_TYPE, property.sourceType)
                if (property.status.isNotBlank()) put(ATTR_STATUS, property.status)
                if (property.propertyType.isNotBlank()) {
                    put(ATTR_PROPERTY_TYPE, property.propertyType)
                }
                if (property.city.isNotBlank()) put(ATTR_CITY, property.city)
                if (property.state.isNotBlank()) put(ATTR_STATE, property.state)
                if (property.zipCode.isNotBlank()) put(ATTR_ZIP, property.zipCode)
            }
        )
    }

    /** Converts a list of rows, dropping unmappable ones; output is ordered by pin id. */
    fun toPins(
        properties: Collection<PropertyEntity>,
        role: MapPinRole = MapPinRole.PROPERTY
    ): List<MapPin> = properties.mapNotNull { toPin(it, role) }.sortedBy { it.id }

    private fun defaultEmphasis(property: PropertyEntity, role: MapPinRole): MapPinEmphasis = when {
        role == MapPinRole.TARGET -> MapPinEmphasis.PRIMARY
        property.isSavedDeal || property.dealScore >= 80 -> MapPinEmphasis.HIGHLIGHTED
        property.status.equals("Off-Market", ignoreCase = true) -> MapPinEmphasis.MUTED
        else -> MapPinEmphasis.NORMAL
    }
}
