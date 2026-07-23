package me.danielstiner.dumble.mumble.channeltree

import me.danielstiner.dumble.mumble.proto.MumbleProtos

/**
 * Pure reducers. Deltas are partial — proto2 has-bits decide field-by-field whether the message
 * carries a new value or leaves the prior one intact. Overwriting an unset field with its proto
 * default would blank a name on an unrelated move.
 */
object ChannelTreeReducers {

    fun applyChannelState(tree: ChannelTree, msg: MumbleProtos.ChannelState): ChannelTree {
        val id = msg.channelId
        val old = tree.channels[id]
        val channel = Channel(
            id = id,
            parentId = if (msg.hasParent()) msg.parent else old?.parentId,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            position = if (msg.hasPosition()) msg.position else old?.position ?: 0,
        )
        return tree.copy(channels = tree.channels + (id to channel))
    }

    fun applyChannelRemove(tree: ChannelTree, msg: MumbleProtos.ChannelRemove): ChannelTree =
        tree.copy(channels = tree.channels - msg.channelId)

    fun applyUserState(tree: ChannelTree, msg: MumbleProtos.UserState): ChannelTree {
        val session = msg.session
        val old = tree.users[session]
        val user = User(
            session = session,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            channelId = if (msg.hasChannelId()) msg.channelId else old?.channelId ?: 0,
            mute = if (msg.hasMute()) msg.mute else old?.mute ?: false,
            deaf = if (msg.hasDeaf()) msg.deaf else old?.deaf ?: false,
            selfMute = if (msg.hasSelfMute()) msg.selfMute else old?.selfMute ?: false,
            selfDeaf = if (msg.hasSelfDeaf()) msg.selfDeaf else old?.selfDeaf ?: false,
            suppress = if (msg.hasSuppress()) msg.suppress else old?.suppress ?: false,
        )
        return tree.copy(users = tree.users + (session to user))
    }

    fun applyUserRemove(tree: ChannelTree, msg: MumbleProtos.UserRemove): ChannelTree =
        tree.copy(users = tree.users - msg.session)
}
