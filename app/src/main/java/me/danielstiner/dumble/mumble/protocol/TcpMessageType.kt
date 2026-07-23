package me.danielstiner.dumble.mumble.protocol

/** Control-channel message types. Ids are protocol-stable; do not renumber. */
enum class TcpMessageType(val id: Int) {
    Version(0), UDPTunnel(1), Authenticate(2), Ping(3), Reject(4), ServerSync(5),
    ChannelRemove(6), ChannelState(7), UserRemove(8), UserState(9), BanList(10),
    TextMessage(11), PermissionDenied(12), ACL(13), QueryUsers(14), CryptSetup(15),
    ContextActionModify(16), ContextAction(17), UserList(18), VoiceTarget(19),
    PermissionQuery(20), CodecVersion(21), UserStats(22), RequestBlob(23),
    ServerConfig(24), SuggestConfig(25), PluginDataTransmission(26);

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Null for ids this client does not model — callers ignore such frames. */
        fun from(id: Int): TcpMessageType? = byId[id]
    }
}
