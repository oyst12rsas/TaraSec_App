import SwiftUI

struct MaintenanceNotice: View {
    private struct Window: Decodable {
        let startsAt: Int64
        let endsAt: Int64

        var start: Date { Date(timeIntervalSince1970: TimeInterval(startsAt)) }
        var end: Date { Date(timeIntervalSince1970: TimeInterval(endsAt)) }
    }

    private struct Response: Decodable {
        let notice: Window?
    }

    @State private var window: Window?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            if let window, context.date < window.end {
                VStack(alignment: .leading, spacing: 4) {
                    Text(context.date < window.start ? "Scheduled maintenance" : "Maintenance in progress")
                        .font(.headline)
                    Text("Service may be disrupted from \(display(window.start)) to \(display(window.end)).")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .foregroundStyle(Color.black)
                .background(Color(red: 1, green: 0.95, blue: 0.78))
            }
        }
        .task {
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    private func display(_ date: Date) -> String {
        date.formatted(.dateTime.year().month().day().hour().minute().timeZone())
    }

    @MainActor
    private func refresh() async {
        guard let url = URL(string: "https://tarasec.org/script/maintenanceNotice.php") else { return }
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              data.count <= 1024 else { return }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard let result = try? decoder.decode(Response.self, from: data) else { return }
        if let next = result.notice {
            guard next.startsAt > 0, next.endsAt > next.startsAt, next.endsAt <= 253402300799 else { return }
        }
        window = result.notice
    }
}
