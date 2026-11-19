import Foundation
import Observation
@preconcurrency import PocketShared

/// Loads real data through `PocketBackend` and publishes it to the shared `PocketLive`
/// store that every facade reads. Views observe `revision` and stay synchronous.
@MainActor
@Observable
final class LiveDataController {
    let backend: PocketBackend
    private(set) var session: AuthenticatedSession?
    /// Bumped after every store change so SwiftUI re-reads the facades.
    private(set) var revision = 0
    private(set) var isLoadingPublic = false
    private(set) var isLoadingPrivate = false
    /// zh-TW message of the most recent failure, safe to show.
    var lastError: String?

    init(backend: PocketBackend) {
        self.backend = backend
    }

    private func publish() { revision += 1 }

    /// Announcements, calendar, weather and YouBike need no account.
    func refreshPublic(weatherSource: WeatherSource, force: Bool = false) async {
        guard !isLoadingPublic else { return }
        isLoadingPublic = true
        defer { isLoadingPublic = false; publish() }
        let live = PocketLive.shared
        do {
            let page = try await backend.call { try await $0.announcements(pageIndex: 1, keyword: "", unitId: "-1", categoryId: "-1", token: $1) }
            live.putAnnouncements(items: page.announcements)
        } catch { lastError = error.message }
        publish()
        do {
            let snapshot = try await backend.call { try await $0.calendar(forceRefresh: force, token: $1) }
            live.putEvents(items: snapshot.feed.events)
        } catch { lastError = error.message }
        publish()
        for location in live.weatherLocations {
            if let load = try? await backend.call({ try await $0.weather(source: weatherSource, location: location, forceRefresh: force, token: $1) }),
               let snapshot = load.snapshot {
                live.putWeather(locationId: location.id, snapshot: snapshot)
            }
        }
        if let loads = try? await backend.call({ try await $0.youBike(stations: live.youBikeStations, forceRefresh: force, token: $1) }) {
            for load in loads { if let snapshot = load.snapshot { live.putYouBike(stationId: load.stationId, snapshot: snapshot) } }
        }
    }
}
