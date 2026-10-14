require "json"
package = JSON.parse(File.read(File.join(__dir__, "package.json")))
metadata = JSON.parse(File.read(File.join(__dir__, "avvessa-native.json")))
Pod::Spec.new do |spec|
  spec.name = "AvvessaReactNative"
  spec.version = package.fetch("version")
  spec.summary = package.fetch("description")
  spec.homepage = package.fetch("homepage")
  spec.license = { :type => "Apache-2.0", :file => "LICENSE" }
  spec.authors = { "SandroX" => "sandroxy" }
  spec.source = { :git => package.fetch("repository").fetch("url"), :tag => metadata.fetch("version") }
  spec.platform = :ios, metadata.dig("native", "ios", "minimumVersion")
  spec.static_framework = true
  spec.requires_arc = true
  spec.source_files = ["ios/*.{h,m,mm}", "ios/Core/Avvessa/**/*.{h,m}",
    "shared/ios/AVBridgeSession.m", "shared/ios/AVNotificationDelegateInstallation.{h,m}", "shared/ios/include/AvvessaBridge.h"]
  spec.public_header_files = ["ios/AVReactNativeLifecycle.h", "ios/Core/Avvessa/include/Avvessa.h"]
  spec.private_header_files = ["ios/Core/Avvessa/AVInternal.h", "shared/ios/include/AvvessaBridge.h", "shared/ios/AVNotificationDelegateInstallation.h"]
  spec.resource_bundles = { "AvvessaPrivacy" => ["ios/Core/Avvessa/Resources/PrivacyInfo.xcprivacy"] }
  spec.frameworks = ["UIKit", "UserNotifications", "Network"]
  spec.libraries = ["z", "resolv"]
  spec.pod_target_xcconfig = { "DEFINES_MODULE" => "YES", "CLANG_CXX_LANGUAGE_STANDARD" => "c++20" }
  spec.dependency "JPush", "= #{metadata.dig('native', 'ios', 'jpush')}"
  spec.dependency "JCore", "= #{metadata.dig('native', 'ios', 'jcore')}"
  if defined?(install_modules_dependencies)
    install_modules_dependencies(spec)
  else
    spec.dependency "React-Core"
    if ENV["RCT_NEW_ARCH_ENABLED"] == "1"
      spec.compiler_flags = "-DRCT_NEW_ARCH_ENABLED=1"
      spec.dependency "React-Codegen"
      spec.dependency "RCTRequired"
      spec.dependency "RCTTypeSafety"
      spec.dependency "ReactCommon/turbomodule/core"
    end
  end
end
