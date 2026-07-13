package me.danielstiner.dumble.data

class FakeServerConfigStore(private var saved: SavedServer = SavedServer()) : ServerConfigStore {
    override fun load(): SavedServer = saved
    override fun save(host: String, port: Int, username: String) {
        saved = SavedServer(host, port, username)
    }
}
