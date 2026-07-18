import Foundation
import SwiftUI
import UIKit
import WebKit

enum PersonalAppBridgeCommand: Equatable {
    case saveState(Data)
    case submitSnapshot(Data)
    case requestOpenURL(URL)
}

enum PersonalAppBridgeMethod: String {
    case saveState
    case submitSnapshot
    case requestOpenURL
}

enum PersonalAppBridgeMessageParser {
    static let expectedHandlerName = "skillforge"
    static let maximumMessageBytes = 64 * 1_024

    static func parse(
        handlerName: String,
        isMainFrame: Bool,
        body: Any,
        expectedArtifactID: String
    ) -> PersonalAppBridgeCommand? {
        guard let method = preflightMethod(
                  handlerName: handlerName,
                  isMainFrame: isMainFrame,
                  body: body,
                  expectedArtifactID: expectedArtifactID
              ),
              let fields = body as? [String: Any],
              JSONSerialization.isValidJSONObject(fields),
              let messageData = try? JSONSerialization.data(withJSONObject: fields),
              messageData.count <= maximumMessageBytes else { return nil }

        switch method {
        case .saveState, .submitSnapshot:
            guard Set(fields.keys) == ["method", "artifactId", "payload"],
                  let payload = fields["payload"],
                  JSONSerialization.isValidJSONObject(payload),
                  let data = try? JSONSerialization.data(withJSONObject: payload),
                  data.count <= maximumMessageBytes else { return nil }
            return method == .saveState ? .saveState(data) : .submitSnapshot(data)

        case .requestOpenURL:
            guard Set(fields.keys) == ["method", "artifactId", "url"],
                  let value = fields["url"] as? String,
                  let url = PersonalAppExternalURLPolicy.validatedURL(value) else { return nil }
            return .requestOpenURL(url)
        }
    }

    static func preflightMethod(
        handlerName: String,
        isMainFrame: Bool,
        body: Any,
        expectedArtifactID: String
    ) -> PersonalAppBridgeMethod? {
        guard handlerName == expectedHandlerName,
              isMainFrame,
              let fields = body as? [String: Any],
              let rawMethod = fields["method"] as? String,
              let method = PersonalAppBridgeMethod(rawValue: rawMethod),
              let artifactID = fields["artifactId"] as? String,
              artifactID == expectedArtifactID else { return nil }
        return method
    }
}

struct PersonalAppBridgeRateLimiter {
    static let minimumSaveInterval: TimeInterval = 0.5
    static let minimumConfirmationInterval: TimeInterval = 2.0

    private var lastSaveAcceptedAt: TimeInterval?
    private var lastConfirmationAcceptedAt: TimeInterval?

    mutating func shouldAccept(_ method: PersonalAppBridgeMethod, now: TimeInterval) -> Bool {
        guard now.isFinite else { return false }
        switch method {
        case .saveState:
            return accept(
                now: now,
                minimumInterval: Self.minimumSaveInterval,
                lastAcceptedAt: &lastSaveAcceptedAt
            )
        case .submitSnapshot, .requestOpenURL:
            return accept(
                now: now,
                minimumInterval: Self.minimumConfirmationInterval,
                lastAcceptedAt: &lastConfirmationAcceptedAt
            )
        }
    }

    private func accept(
        now: TimeInterval,
        minimumInterval: TimeInterval,
        lastAcceptedAt: inout TimeInterval?
    ) -> Bool {
        if let lastAcceptedAt,
           now < lastAcceptedAt || now - lastAcceptedAt < minimumInterval {
            return false
        }
        lastAcceptedAt = now
        return true
    }
}

struct PersonalAppInitialNavigationGate {
    private var didAllowInitialDocument = false

    mutating func rearmForSecuredDocument() {
        didAllowInitialDocument = false
    }

    mutating func shouldAllow(
        isOtherNavigation: Bool,
        isMainFrame: Bool,
        scheme: String?
    ) -> Bool {
        guard !didAllowInitialDocument,
              isOtherNavigation,
              isMainFrame,
              scheme?.lowercased() == "about" else { return false }
        didAllowInitialDocument = true
        return true
    }
}

enum PersonalAppDeniedPermissionKind: String, Hashable {
    case fileSelection
    case mediaCapture
    case deviceOrientationAndMotion
}

enum PersonalAppWebPermissionPolicy {
    static var openPanelResult: [URL]? { nil }
    static var permissionDecision: WKPermissionDecision { .deny }
}

enum PersonalAppFileInputGuard {
    static let script = #"""
    (function() {
      'use strict';
      const nativeApply = Reflect.apply;
      const nativeDefineProperty = Object.defineProperty;
      const nativeGetOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
      const nativeHTMLElementClick = HTMLElement.prototype.click;
      const inputPrototype = HTMLInputElement.prototype;
      const nativeInputClick = inputPrototype.click;
      const nativeShowPicker = inputPrototype.showPicker;
      const nativeSetAttribute = Element.prototype.setAttribute;
      const nativeSetAttributeNS = Element.prototype.setAttributeNS;
      const nativeHasAttribute = Element.prototype.hasAttribute;
      const nativeLocalNameGetter = nativeApply(
        nativeGetOwnPropertyDescriptor,
        Object,
        [Element.prototype, 'localName']
      ).get;
      const nativeNodeTypeGetter = nativeApply(
        nativeGetOwnPropertyDescriptor,
        Object,
        [Node.prototype, 'nodeType']
      ).get;
      const nativeTypeDescriptor = nativeApply(
        nativeGetOwnPropertyDescriptor,
        Object,
        [inputPrototype, 'type']
      );
      const nativeCaptureDescriptor = nativeApply(
        nativeGetOwnPropertyDescriptor,
        Object,
        [inputPrototype, 'capture']
      );
      const nativeDisabledSetter = nativeApply(
        nativeGetOwnPropertyDescriptor,
        Object,
        [inputPrototype, 'disabled']
      ).set;
      const nativeToLowerCase = String.prototype.toLowerCase;
      const nativeComposedPath = Event.prototype.composedPath;
      const nativeTargetGetter = nativeApply(
        nativeGetOwnPropertyDescriptor,
        Object,
        [Event.prototype, 'target']
      ).get;
      const nativePreventDefault = Event.prototype.preventDefault;
      const nativeStopImmediatePropagation = Event.prototype.stopImmediatePropagation;
      const nativeAddEventListener = EventTarget.prototype.addEventListener;
      const nativeDispatchEvent = EventTarget.prototype.dispatchEvent;
      const NativeCustomEvent = CustomEvent;
      const NativeMutationObserver = MutationObserver;
      const nativeMutationObserve = MutationObserver.prototype.observe;
      const nativeElementQuerySelectorAll = Element.prototype.querySelectorAll;
      const nativeDocumentQuerySelectorAll = Document.prototype.querySelectorAll;
      const nativeFragmentQuerySelectorAll = DocumentFragment.prototype.querySelectorAll;

      const nativeCall = (method, receiver, args) => nativeApply(method, receiver, args);
      const nodeType = value => {
        try {
          return nativeCall(nativeNodeTypeGetter, value, []);
        } catch (_) {
          return 0;
        }
      };
      const isInput = value => {
        if (nodeType(value) !== 1) return false;
        try {
          return nativeCall(nativeLocalNameGetter, value, []) === 'input';
        } catch (_) {
          return false;
        }
      };
      const isDeniedInput = value => {
        if (!isInput(value)) return false;
        try {
          const type = nativeCall(nativeTypeDescriptor.get, value, []);
          const normalizedType = nativeCall(nativeToLowerCase, type, []);
          return normalizedType === 'file' ||
            nativeCall(nativeHasAttribute, value, ['capture']);
        } catch (_) {
          return true;
        }
      };
      const disableDeniedInput = value => {
        if (!isDeniedInput(value)) return false;
        try {
          nativeCall(nativeDisabledSetter, value, [true]);
        } catch (_) {}
        return true;
      };
      const reportDenial = () => {
        const event = new NativeCustomEvent(
          'skillforgepermissiondenied',
          {detail: {kind: 'fileSelection'}}
        );
        nativeCall(nativeDispatchEvent, document, [event]);
      };
      const block = value => {
        if (!disableDeniedInput(value)) return false;
        reportDenial();
        return true;
      };
      const installLockedMethod = (target, name, method) => {
        nativeCall(nativeDefineProperty, Object, [target, name, {
          value: method,
          writable: false,
          configurable: false
        }]);
      };
      const installLockedAccessor = (target, name, descriptor) => {
        nativeCall(nativeDefineProperty, Object, [target, name, descriptor]);
      };

      installLockedMethod(HTMLElement.prototype, 'click', function(...args) {
        if (block(this)) return;
        return nativeCall(nativeHTMLElementClick, this, args);
      });
      installLockedMethod(inputPrototype, 'click', function(...args) {
        if (block(this)) return;
        return nativeCall(nativeInputClick, this, args);
      });
      if (typeof nativeShowPicker === 'function') {
        installLockedMethod(inputPrototype, 'showPicker', function(...args) {
          if (block(this)) return;
          return nativeCall(nativeShowPicker, this, args);
        });
      }
      installLockedAccessor(inputPrototype, 'type', {
        get: nativeTypeDescriptor.get,
        set: function(value) {
          nativeCall(nativeTypeDescriptor.set, this, [value]);
          disableDeniedInput(this);
        },
        enumerable: nativeTypeDescriptor.enumerable,
        configurable: false
      });
      if (nativeCaptureDescriptor && typeof nativeCaptureDescriptor.set === 'function') {
        installLockedAccessor(inputPrototype, 'capture', {
          get: nativeCaptureDescriptor.get,
          set: function(value) {
            nativeCall(nativeCaptureDescriptor.set, this, [value]);
            disableDeniedInput(this);
          },
          enumerable: nativeCaptureDescriptor.enumerable,
          configurable: false
        });
      }
      installLockedMethod(Element.prototype, 'setAttribute', function(...args) {
        const result = nativeCall(nativeSetAttribute, this, args);
        disableDeniedInput(this);
        return result;
      });
      installLockedMethod(Element.prototype, 'setAttributeNS', function(...args) {
        const result = nativeCall(nativeSetAttributeNS, this, args);
        disableDeniedInput(this);
        return result;
      });

      const blockDeniedClick = event => {
        let path;
        try {
          path = nativeCall(nativeComposedPath, event, []);
        } catch (_) {
          path = [nativeCall(nativeTargetGetter, event, [])];
        }
        let deniedInput = null;
        for (let index = 0; index < path.length; index += 1) {
          if (isDeniedInput(path[index])) {
            deniedInput = path[index];
            break;
          }
        }
        if (!deniedInput) return;
        nativeCall(nativePreventDefault, event, []);
        nativeCall(nativeStopImmediatePropagation, event, []);
        block(deniedInput);
      };
      nativeCall(nativeAddEventListener, window, ['click', blockDeniedClick, true]);

      const disableDeniedInputs = root => {
        disableDeniedInput(root);
        let querySelectorAll = null;
        switch (nodeType(root)) {
        case 1:
          querySelectorAll = nativeElementQuerySelectorAll;
          break;
        case 9:
          querySelectorAll = nativeDocumentQuerySelectorAll;
          break;
        case 11:
          querySelectorAll = nativeFragmentQuerySelectorAll;
          break;
        default:
          return;
        }
        const deniedInputs = nativeCall(
          querySelectorAll,
          root,
          ['input[type="file"], input[capture]']
        );
        for (let index = 0; index < deniedInputs.length; index += 1) {
          disableDeniedInput(deniedInputs[index]);
        }
      };
      const observer = new NativeMutationObserver(records => {
        for (let recordIndex = 0; recordIndex < records.length; recordIndex += 1) {
          const record = records[recordIndex];
          disableDeniedInputs(record.target);
          for (let nodeIndex = 0; nodeIndex < record.addedNodes.length; nodeIndex += 1) {
            disableDeniedInputs(record.addedNodes[nodeIndex]);
          }
        }
      });
      nativeCall(nativeMutationObserve, observer, [document, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['type', 'capture']
      }]);
      disableDeniedInputs(document);
    })();
    """#
}

enum PersonalAppClipboardGuard {
    static let script = #"""
    (function() {
      'use strict';
      const nativeApply = Reflect.apply;
      const nativeDefineProperty = Object.defineProperty;
      const nativeGetOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
      const nativeFreeze = Object.freeze;
      const NativeSet = Set;
      const nativeSetHas = Set.prototype.has;
      const nativeString = String;
      const nativeToLowerCase = String.prototype.toLowerCase;
      const nativeAddEventListener = EventTarget.prototype.addEventListener;
      const nativeDispatchEvent = EventTarget.prototype.dispatchEvent;
      const nativePreventDefault = Event.prototype.preventDefault;
      const nativeStopImmediatePropagation = Event.prototype.stopImmediatePropagation;
      const NativeCustomEvent = CustomEvent;
      const NativePromise = Promise;
      const nativePromiseReject = Promise.reject;
      const NativeDOMException = DOMException;
      const nativeCall = (method, receiver, args) => nativeApply(method, receiver, args);
      const blockedCommands = new NativeSet(['copy', 'cut', 'paste']);
      const reportDenial = () => {
        const event = new NativeCustomEvent(
          'skillforgepermissiondenied',
          {detail: {kind: 'clipboard'}}
        );
        nativeCall(nativeDispatchEvent, document, [event]);
      };
      const installLockedMethod = (target, name, value) => {
        try {
          nativeCall(nativeDefineProperty, Object, [target, name, {
            value: value,
            writable: false,
            configurable: false
          }]);
          const descriptor = nativeCall(
            nativeGetOwnPropertyDescriptor,
            Object,
            [target, name]
          );
          return descriptor && descriptor.value === value;
        } catch (_) {
          return false;
        }
      };

      const documentPrototype = Document.prototype;
      const nativeExecCommand = documentPrototype.execCommand;
      const guardedExecCommand = function(command, ...argumentsList) {
        let normalizedCommand;
        try {
          const commandString = nativeCall(nativeString, undefined, [command]);
          normalizedCommand = nativeCall(nativeToLowerCase, commandString, []);
        } catch (_) {
          reportDenial();
          return false;
        }
        if (nativeCall(nativeSetHas, blockedCommands, [normalizedCommand])) {
          reportDenial();
          return false;
        }
        return typeof nativeExecCommand === 'function'
          ? nativeCall(nativeExecCommand, this, [command, ...argumentsList])
          : false;
      };
      if (!installLockedMethod(documentPrototype, 'execCommand', guardedExecCommand)) {
        installLockedMethod(document, 'execCommand', guardedExecCommand);
      }

      const clipboardEventTypes = ['copy', 'cut', 'paste'];
      for (let index = 0; index < clipboardEventTypes.length; index += 1) {
        nativeCall(nativeAddEventListener, window, [clipboardEventTypes[index], event => {
          nativeCall(nativePreventDefault, event, []);
          nativeCall(nativeStopImmediatePropagation, event, []);
          reportDenial();
        }, true]);
      }

      const deniedClipboardMethod = function() {
        reportDenial();
        const error = new NativeDOMException('Clipboard access denied', 'NotAllowedError');
        return nativeCall(nativePromiseReject, NativePromise, [error]);
      };
      const lockClipboardMethods = target => {
        if (!target) return;
        const methodNames = ['read', 'readText', 'write', 'writeText'];
        for (let index = 0; index < methodNames.length; index += 1) {
          const name = methodNames[index];
          if (typeof target[name] === 'function') {
            installLockedMethod(target, name, deniedClipboardMethod);
          }
        }
        try { nativeCall(nativeFreeze, Object, [target]); } catch (_) {}
      };
      if (typeof globalThis.Clipboard === 'function') {
        lockClipboardMethods(globalThis.Clipboard.prototype);
      }
      lockClipboardMethods(navigator['clip' + 'board']);
    })();
    """#
}

enum PersonalAppExternalURLPolicy {
    static let maximumURLBytes = 2_048

    static func validatedURL(_ rawValue: String) -> URL? {
        guard !rawValue.isEmpty,
              rawValue.utf8.count <= maximumURLBytes,
              rawValue == rawValue.trimmingCharacters(in: .whitespacesAndNewlines),
              !rawValue.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              !rawValue.contains("\\"),
              hasValidPercentEscapes(rawValue),
              let authority = rawAuthority(in: rawValue),
              authority.utf8.allSatisfy({ $0 < 0x80 }),
              !authority.contains("%"),
              let rawHost = rawHost(in: authority),
              isConservativeASCIIHost(rawHost),
              var components = URLComponents(string: rawValue),
              let originalScheme = components.scheme,
              ["http", "https"].contains(originalScheme.lowercased()),
              let originalHost = components.host,
              !originalHost.isEmpty,
              components.user == nil,
              components.password == nil else { return nil }

        components.scheme = originalScheme.lowercased()
        components.host = originalHost.lowercased()
        guard let url = components.url,
              url.absoluteString.utf8.count <= maximumURLBytes,
              let normalizedHost = URLComponents(url: url, resolvingAgainstBaseURL: false)?.host,
              !normalizedHost.isEmpty else { return nil }
        return url
    }

    private static func rawAuthority(in value: String) -> Substring? {
        guard let delimiter = value.range(of: "://") else { return nil }
        let remainder = value[delimiter.upperBound...]
        let end = remainder.firstIndex(where: { ["/", "?", "#"].contains($0) })
            ?? value.endIndex
        let authority = value[delimiter.upperBound..<end]
        return authority.isEmpty ? nil : authority
    }

    private static func rawHost(in authority: Substring) -> Substring? {
        guard !authority.contains("@"), !authority.hasPrefix("[") else { return nil }
        let parts = authority.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 1 || parts.count == 2,
              let host = parts.first,
              !host.isEmpty else { return nil }
        if parts.count == 2 {
            let port = parts[1]
            guard !port.isEmpty, port.allSatisfy(\.isNumber) else { return nil }
        }
        return host
    }

    private static func isConservativeASCIIHost(_ host: Substring) -> Bool {
        guard !host.isEmpty, host.utf8.count <= 253 else { return false }
        let bytes = Array(host.utf8)
        if bytes.allSatisfy({ isASCIIDigit($0) || $0 == 0x2E }) {
            return isStandardIPv4(host)
        }

        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        return labels.allSatisfy { label in
            guard !label.isEmpty, label.utf8.count <= 63,
                  let first = label.utf8.first,
                  let last = label.utf8.last,
                  isASCIIAlphanumeric(first),
                  isASCIIAlphanumeric(last) else { return false }
            return label.utf8.allSatisfy { byte in
                isASCIIAlphanumeric(byte) || byte == 0x2D
            }
        }
    }

    private static func isStandardIPv4(_ host: Substring) -> Bool {
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return false }
        return octets.allSatisfy { octet in
            guard !octet.isEmpty,
                  octet.utf8.count <= 3,
                  octet.utf8.allSatisfy(isASCIIDigit),
                  !(octet.count > 1 && octet.first == "0"),
                  let value = Int(octet),
                  value <= 255 else { return false }
            return true
        }
    }

    private static func isASCIIAlphanumeric(_ byte: UInt8) -> Bool {
        isASCIIDigit(byte)
            || (0x41...0x5A).contains(byte)
            || (0x61...0x7A).contains(byte)
    }

    private static func isASCIIDigit(_ byte: UInt8) -> Bool {
        (0x30...0x39).contains(byte)
    }

    private static func hasValidPercentEscapes(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        var index = 0
        while index < bytes.count {
            if bytes[index] == 0x25 {
                guard index + 2 < bytes.count,
                      isHexDigit(bytes[index + 1]),
                      isHexDigit(bytes[index + 2]) else { return false }
                index += 3
            } else {
                index += 1
            }
        }
        return true
    }

    private static func isHexDigit(_ byte: UInt8) -> Bool {
        (0x30...0x39).contains(byte)
            || (0x41...0x46).contains(byte)
            || (0x61...0x66).contains(byte)
    }
}

enum PersonalAppHTMLCSPInserter {
    static let policy = "default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; "
        + "script-src 'unsafe-inline'; connect-src 'none'; font-src data:; media-src data: blob:; "
        + "form-action 'none'; frame-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'none';"

    private static var metaTag: String {
        "<meta http-equiv=\"Content-Security-Policy\" content=\"\(policy)\">"
    }

    static func secure(_ html: String) -> String {
        let prefix = scanDocumentPrefix(html)
        if let headEnd = prefix.headEnd {
            return inserting(metaTag, atUTF8Offset: headEnd, in: html)
        }

        let syntheticHead = "<head>\(metaTag)</head>"
        let insertionOffset = prefix.htmlEnd ?? prefix.doctypeEnd ?? prefix.documentStart
        return inserting(syntheticHead, atUTF8Offset: insertionOffset, in: html)
    }

    private struct DocumentPrefix {
        let documentStart: Int
        var doctypeEnd: Int?
        var htmlEnd: Int?
        var headEnd: Int?
    }

    private static func inserting(_ value: String, atUTF8Offset offset: Int, in html: String) -> String {
        let utf8Index = html.utf8.index(html.utf8.startIndex, offsetBy: offset)
        guard let index = String.Index(utf8Index, within: html) else { return value + html }
        var result = html
        result.insert(contentsOf: value, at: index)
        return result
    }

    private static func scanDocumentPrefix(_ html: String) -> DocumentPrefix {
        let bytes = Array(html.utf8)
        let documentStart = starts(with: [0xEF, 0xBB, 0xBF], at: 0, in: bytes) ? 3 : 0
        var result = DocumentPrefix(
            documentStart: documentStart,
            doctypeEnd: nil,
            htmlEnd: nil,
            headEnd: nil
        )
        var cursor = documentStart

        while true {
            while cursor < bytes.count, isHTMLWhitespace(bytes[cursor]) {
                cursor += 1
            }

            if starts(with: [0x3C, 0x21, 0x2D, 0x2D], at: cursor, in: bytes) {
                guard let commentEnd = firstOccurrence(
                    of: [0x2D, 0x2D, 0x3E],
                    after: cursor + 4,
                    in: bytes
                ) else { return result }
                cursor = commentEnd + 3
                continue
            }

            if result.doctypeEnd == nil,
               result.htmlEnd == nil,
               let doctypeEnd = openingTagEnd(named: "!doctype", at: cursor, in: bytes) {
                result.doctypeEnd = doctypeEnd
                cursor = doctypeEnd
                continue
            }

            if result.htmlEnd == nil,
               let htmlEnd = openingTagEnd(named: "html", at: cursor, in: bytes) {
                result.htmlEnd = htmlEnd
                cursor = htmlEnd
                continue
            }

            if let headEnd = openingTagEnd(named: "head", at: cursor, in: bytes) {
                result.headEnd = headEnd
            }
            // Any non-prefix text, declaration, or element means an explicit head discovered later
            // would already be too late. The caller inserts a synthetic head before this construct.
            return result
        }
    }

    private static func openingTagEnd(named name: String, at lessThan: Int, in bytes: [UInt8]) -> Int? {
        let nameBytes = Array(name.utf8).map(asciiLowercased)
        let nameStart = lessThan + 1
        let nameEnd = nameStart + nameBytes.count
        guard lessThan >= 0,
              lessThan < bytes.count,
              bytes[lessThan] == 0x3C,
              nameEnd < bytes.count,
              zip(bytes[nameStart..<nameEnd], nameBytes).allSatisfy({ asciiLowercased($0.0) == $0.1 }),
              isTagNameBoundary(bytes[nameEnd]),
              let end = endOfTag(startingAt: nameEnd, in: bytes) else { return nil }
        return end + 1
    }

    private static func endOfTag(startingAt start: Int, in bytes: [UInt8]) -> Int? {
        var quote: UInt8?
        var cursor = start
        while cursor < bytes.count {
            let byte = bytes[cursor]
            if let activeQuote = quote {
                if byte == activeQuote { quote = nil }
            } else if byte == 0x22 || byte == 0x27 {
                quote = byte
            } else if byte == 0x3E {
                return cursor
            }
            cursor += 1
        }
        return nil
    }

    private static func firstOccurrence(
        of needle: [UInt8],
        after start: Int,
        in bytes: [UInt8]
    ) -> Int? {
        guard !needle.isEmpty, start <= bytes.count - needle.count else { return nil }
        for index in start...(bytes.count - needle.count) where starts(with: needle, at: index, in: bytes) {
            return index
        }
        return nil
    }

    private static func starts(with needle: [UInt8], at index: Int, in bytes: [UInt8]) -> Bool {
        guard index >= 0, index + needle.count <= bytes.count else { return false }
        return bytes[index..<(index + needle.count)].elementsEqual(needle)
    }

    private static func isTagNameBoundary(_ byte: UInt8) -> Bool {
        byte == 0x3E || byte == 0x2F || isHTMLWhitespace(byte)
    }

    private static func isHTMLWhitespace(_ byte: UInt8) -> Bool {
        [0x09, 0x0A, 0x0C, 0x0D, 0x20].contains(byte)
    }

    private static func asciiLowercased(_ byte: UInt8) -> UInt8 {
        (0x41...0x5A).contains(byte) ? byte + 0x20 : byte
    }
}

enum PersonalAppPendingConfirmation: Equatable {
    case snapshot(Data)
    case externalURL(URL)
}

struct PersonalAppConfirmationState: Equatable {
    static let cooldownInterval: TimeInterval = 2.0

    private(set) var pending: PersonalAppPendingConfirmation?
    private(set) var cooldownUntil: TimeInterval?

    @discardableResult
    mutating func propose(
        _ confirmation: PersonalAppPendingConfirmation,
        now: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> Bool {
        guard pending == nil,
              cooldownUntil.map({ now >= $0 }) ?? true else { return false }
        pending = confirmation
        return true
    }

    mutating func cancel(now: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        guard pending != nil else { return }
        pending = nil
        cooldownUntil = now + Self.cooldownInterval
    }

    mutating func takeSnapshot(
        now: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> Data? {
        guard case .snapshot(let data) = pending else { return nil }
        pending = nil
        cooldownUntil = now + Self.cooldownInterval
        return data
    }

    mutating func takeExternalURL(
        now: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> URL? {
        guard case .externalURL(let url) = pending else { return nil }
        pending = nil
        cooldownUntil = now + Self.cooldownInterval
        return url
    }
}

struct PersonalAppExternalURLOpener: Sendable {
    private let handler: @MainActor @Sendable (URL) -> Void

    init(_ handler: @escaping @MainActor @Sendable (URL) -> Void) {
        self.handler = handler
    }

    @MainActor
    func open(_ url: URL) {
        handler(url)
    }
}

private struct PersonalAppExternalURLOpenerKey: EnvironmentKey {
    static let defaultValue = PersonalAppExternalURLOpener { url in
        UIApplication.shared.open(url)
    }
}

extension EnvironmentValues {
    var personalAppExternalURLOpener: PersonalAppExternalURLOpener {
        get { self[PersonalAppExternalURLOpenerKey.self] }
        set { self[PersonalAppExternalURLOpenerKey.self] = newValue }
    }
}
