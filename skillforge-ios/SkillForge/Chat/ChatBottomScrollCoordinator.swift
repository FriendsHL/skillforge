import SwiftUI
import UIKit

struct ChatBottomScrollRequest: Equatable, Sendable {
    let id: UInt64
    let sessionID: String
    let generation: UInt64
}

@MainActor
protocol ChatBottomScrollDriving: AnyObject {
    func stopActiveScrolling()
}

@MainActor
final class ChatBottomScrollCoordinator: ObservableObject {
    @Published private(set) var completedRequestID: UInt64 = 0

    private(set) var activeSessionID: String?
    private(set) var generation: UInt64 = 0
    private(set) var pendingRequest: ChatBottomScrollRequest?
    private var pendingPositionAtBottom: (() -> Void)?
    private weak var driver: (any ChatBottomScrollDriving)?
    private var nextRequestID: UInt64 = 0
    private var isPerformingRequest = false

    var hasPendingRequest: Bool {
        pendingRequest != nil
    }

    init(sessionID: String?) {
        activeSessionID = sessionID
    }

    func activate(sessionID: String?) {
        guard sessionID != activeSessionID else { return }
        generation &+= 1
        activeSessionID = sessionID
        pendingRequest = nil
        pendingPositionAtBottom = nil
    }

    func attach(_ driver: any ChatBottomScrollDriving) {
        self.driver = driver
        performPendingRequestIfPossible()
    }

    func detach(_ driver: any ChatBottomScrollDriving) {
        guard let current = self.driver, current === driver else { return }
        self.driver = nil
    }

    @discardableResult
    func request(
        sessionID: String?,
        positionAtBottom: @escaping () -> Void
    ) -> ChatBottomScrollRequest? {
        guard let sessionID, sessionID == activeSessionID else { return nil }
        nextRequestID &+= 1
        let request = ChatBottomScrollRequest(
            id: nextRequestID,
            sessionID: sessionID,
            generation: generation
        )
        pendingRequest = request
        pendingPositionAtBottom = positionAtBottom
        performPendingRequestIfPossible()
        return request
    }

    func cancelPendingRequest() {
        generation &+= 1
        pendingRequest = nil
        pendingPositionAtBottom = nil
    }

    func retryPendingRequest() {
        performPendingRequestIfPossible()
    }

    private func performPendingRequestIfPossible() {
        guard !isPerformingRequest,
              let request = pendingRequest,
              request.sessionID == activeSessionID,
              request.generation == generation,
              let positionAtBottom = pendingPositionAtBottom,
              let driver
        else { return }

        isPerformingRequest = true
        defer { isPerformingRequest = false }

        driver.stopActiveScrolling()
        positionAtBottom()

        guard pendingRequest == request,
              request.sessionID == activeSessionID,
              request.generation == generation
        else { return }

        pendingRequest = nil
        pendingPositionAtBottom = nil
        completedRequestID = request.id
    }
}

@MainActor
final class UIKitChatBottomScrollDriver: ChatBottomScrollDriving {
    private weak var scrollView: UIScrollView?

    init(scrollView: UIScrollView) {
        self.scrollView = scrollView
    }

    func stopActiveScrolling() {
        guard let scrollView else { return }
        if #available(iOS 17.4, *) {
            scrollView.stopScrollingAndZooming()
        } else {
            let currentOffset = scrollView.contentOffset
            if scrollView.isScrollEnabled {
                scrollView.isScrollEnabled = false
                scrollView.isScrollEnabled = true
            }
            scrollView.setContentOffset(currentOffset, animated: false)
        }
    }

}

struct ChatBottomScrollResolver: UIViewRepresentable {
    let coordinator: ChatBottomScrollCoordinator

    func makeUIView(context: Context) -> ChatBottomScrollProbeView {
        let view = ChatBottomScrollProbeView()
        view.configure(coordinator: coordinator)
        return view
    }

    func updateUIView(_ uiView: ChatBottomScrollProbeView, context: Context) {
        uiView.configure(coordinator: coordinator)
    }

    static func dismantleUIView(_ uiView: ChatBottomScrollProbeView, coordinator: ()) {
        uiView.disconnect()
    }
}

@MainActor
final class ChatBottomScrollProbeView: UIView {
    private weak var scrollCoordinator: ChatBottomScrollCoordinator?
    private var scrollDriver: UIKitChatBottomScrollDriver?
    private weak var resolvedScrollView: UIScrollView?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(coordinator: ChatBottomScrollCoordinator) {
        if scrollCoordinator !== coordinator {
            disconnect()
            scrollCoordinator = coordinator
        }
        resolveScrollViewIfNeeded()
    }

    func disconnect() {
        if let scrollDriver {
            scrollCoordinator?.detach(scrollDriver)
        }
        scrollDriver = nil
        resolvedScrollView = nil
        scrollCoordinator = nil
    }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        resolveScrollViewIfNeeded()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        resolveScrollViewIfNeeded()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        resolveScrollViewIfNeeded()
    }

    private func resolveScrollViewIfNeeded() {
        guard let scrollCoordinator else { return }
        var ancestor = superview
        while let view = ancestor, !(view is UIScrollView) {
            ancestor = view.superview
        }
        guard let scrollView = ancestor as? UIScrollView else { return }
        guard scrollView !== resolvedScrollView else {
            scrollCoordinator.retryPendingRequest()
            return
        }

        if let scrollDriver {
            scrollCoordinator.detach(scrollDriver)
        }
        let driver = UIKitChatBottomScrollDriver(scrollView: scrollView)
        resolvedScrollView = scrollView
        scrollDriver = driver
        scrollCoordinator.attach(driver)
    }
}
