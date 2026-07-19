package me.danielstiner.dumble.mumble.model

/** Server label for display: the root channel's name, or [hostFallback] if it's blank or murmur's
 *  default "Root" (its literal name for an unregistered server). */
fun serverLabel(model: ServerModel, hostFallback: String): String {
    val rootName = model.channels.values.firstOrNull { it.parentId == null }?.name
    return rootName?.takeIf { it.isNotBlank() && it != "Root" } ?: hostFallback
}

/** The name of the channel the local user is currently in, or null if unknown (pre-ServerSync, or a
 *  missing user/channel). */
fun currentChannelName(model: ServerModel): String? {
    val myChannelId = model.sessionId?.let { model.users[it]?.channelId } ?: return null
    return model.channels[myChannelId]?.name
}
