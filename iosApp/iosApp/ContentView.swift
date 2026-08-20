import ComposeApp
import SwiftUI
import UIKit

/// Hosts the shared Compose UI and hands it the iOS speech recognizer.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(transcriber: IosTranscriber())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea(.all)
    }
}
