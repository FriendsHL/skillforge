import UIKit

enum PairingScannerAvailability {
    static var canUseCameraScanner: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return UIImagePickerController.isSourceTypeAvailable(.camera)
        #endif
    }

    static var unavailableMessage: String {
        #if targetEnvironment(simulator)
        return "iOS Simulator has no camera. Copy the QR payload from Dashboard and paste it below."
        #else
        return "Camera is unavailable on this device. Copy the QR payload from Dashboard and paste it below."
        #endif
    }
}
