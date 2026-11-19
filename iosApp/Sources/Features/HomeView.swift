import PocketShared
import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let home = model.dataSource.home(clock: model.clock)
        ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                greeting
                PocketColumns {
                    VStack(spacing: PocketMetrics.cardSpacing) {
                        if model.isSignedIn, let hero = home.hero {
                            CourseHeroCard(hero: hero)
                        } else {
                            GuestHeroCard()
                        }
                        HStack(alignment: .top, spacing: PocketMetrics.cardSpacing) {
                            WeatherCard(weather: home.weather)
                            CountdownCard(countdowns: home.countdowns)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        YouBikeCard(stations: home.youBike)
                    }
                } trailing: {
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "行事曆") {
                            PocketMoreLink(value: PocketRoute.calendar)
                        }
                        PocketCard(padding: 8) {
                            EventRows(events: home.events)
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "最新公告") {
                            PocketMoreLink(value: PocketRoute.announcements)
                        }
                        PocketCard(padding: 8) {
                            AnnouncementRows(announcements: home.announcements)
                        }
                    }
                }
            }
            .pocketPage()
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { brandItem }
    }

    @ToolbarContentBuilder
    private var brandItem: some ToolbarContent {
        if #available(iOS 26.0, *) {
            ToolbarItem(placement: .topBarLeading) { PocketBrandMark() }
                .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem(placement: .topBarLeading) { PocketBrandMark() }
        }
    }

    private var greeting: some View {
        let clock = model.clock
        let hour = Int(clock.minuteOfDay) / 60
        let salutation = switch hour {
        case 5..<11: "早安"
        case 11..<14: "午安"
        case 14..<18: "下午好"
        default: "晚安"
        }
        let weekday = ["一", "二", "三", "四", "五", "六", "日"][Int(clock.dayOfWeek) - 1]
        let name = model.isSignedIn ? "，\(model.profile.studentName)" : ""
        return VStack(alignment: .leading, spacing: 4) {
            Text("\(clock.month) 月 \(clock.day) 日 週\(weekday)")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(PocketColor.onSurfaceVariant)
            Text("\(salutation)\(name)")
                .font(PocketFont.display(30, relativeTo: .largeTitle))
                .foregroundStyle(PocketColor.onSurface)
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
    }
}

private struct CourseHeroCard: View {
    let hero: PocketCourseHero

    var body: some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    if hero.isInClass {
                        Circle()
                            .fill(PocketColor.primary)
                            .frame(width: 8, height: 8)
                    }
                    Text(hero.headline)
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))

                Text(hero.courseName)
                    .font(PocketFont.hero)
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                    .padding(.top, 6)

                Label(hero.supportingText, systemImage: "mappin.and.ellipse")
                    .font(.body)
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    .padding(.top, 4)

                if hero.progress >= 0 {
                    ProgressView(value: hero.progress)
                        .tint(PocketColor.primary)
                        .scaleEffect(x: 1, y: 1.6, anchor: .center)
                        .padding(.top, 20)
                        .accessibilityLabel("本節進度")
                }

                Text(hero.timeText)
                    .font(.footnote.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    .padding(.top, hero.progress >= 0 ? 10 : 18)

                if let next = hero.nextLine {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.turn.down.right")
                            .imageScale(.small)
                        Text(next)
                            .monospacedDigit()
                    }
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(PocketColor.background.opacity(0.55), in: .rect(cornerRadius: 14, style: .continuous))
                    .padding(.top, 16)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct GuestHeroCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("登入後顯示今日課程")
                    .font(PocketFont.display(24, relativeTo: .title2))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                Text("課表與成績需要登入。校園公告、行事曆與天氣可直接瀏覽。")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                Button {
                    model.enterDemo()
                } label: {
                    Text("使用示範帳號")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18)
                        .frame(minHeight: 44)
                        .foregroundStyle(PocketColor.onPrimary)
                        .background(PocketColor.primary, in: .capsule)
                }
                .padding(.top, 8)
            }
        }
    }
}

private struct WeatherCard: View {
    let weather: PocketWeather

    private var symbol: String {
        switch weather.kind {
        case .sunny: "sun.max.fill"
        case .partlyCloudy: "cloud.sun.fill"
        case .cloudy: "cloud.fill"
        case .rain: "cloud.rain.fill"
        default: "cloud.bolt.rain.fill"
        }
    }

    var body: some View {
        PocketCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(weather.location)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    Spacer()
                    Image(systemName: symbol)
                        .symbolRenderingMode(.multicolor)
                        .font(.title2)
                        .accessibilityHidden(true)
                }
                Text(weather.temperatureText)
                    .font(PocketFont.display(44, relativeTo: .largeTitle))
                    .foregroundStyle(PocketColor.onSurface)
                    .padding(.top, 8)
                Text(weather.condition)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(PocketColor.onSurface)
                Spacer(minLength: 12)
                Text("\(weather.highText) / \(weather.lowText) · 降雨 \(weather.rainText)")
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct CountdownCard: View {
    let countdowns: [PocketCountdown]

    var body: some View {
        PocketCard(fill: PocketColor.tertiaryContainer) {
            VStack(alignment: .leading, spacing: 0) {
                Text("倒數")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onTertiaryContainer.opacity(0.85))
                if let first = countdowns.first {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(first.label)
                            .font(PocketFont.display(44, relativeTo: .largeTitle))
                        Text("天")
                            .font(.headline)
                    }
                    .foregroundStyle(PocketColor.onTertiaryContainer)
                    .padding(.top, 8)
                    Text(first.title)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(PocketColor.onTertiaryContainer)
                }
                Spacer(minLength: 12)
                if countdowns.count > 1 {
                    let next = countdowns[1]
                    Text("\(next.title) · \(next.label) 天")
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.onTertiaryContainer.opacity(0.85))
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct YouBikeCard: View {
    let stations: [PocketYouBikeStation]

    var body: some View {
        PocketCard(padding: 8) {
            VStack(spacing: 0) {
                HStack {
                    Label("YouBike", systemImage: "bicycle")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    Spacer()
                    Text("可借 / 可還")
                        .font(.caption)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
                .padding(.horizontal, 12)
                .padding(.top, 10)
                .padding(.bottom, 6)
                ForEach(Array(stations.enumerated()), id: \.element.id) { index, station in
                    if index > 0 { PocketRowDivider().padding(.leading, 12) }
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(station.name)
                                .font(.body.weight(.medium))
                                .foregroundStyle(PocketColor.onSurface)
                            Text(station.detailText)
                                .font(.caption)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                        Spacer(minLength: 8)
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(station.bikesText)
                                .font(PocketFont.display(24, relativeTo: .title2))
                                .foregroundStyle(PocketColor.primary)
                            Text("/ \(station.docksText)")
                                .font(.subheadline)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                        .monospacedDigit()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(station.name)，可借 \(station.bikesText) 輛，可還 \(station.docksText) 格")
                }
            }
        }
    }
}
