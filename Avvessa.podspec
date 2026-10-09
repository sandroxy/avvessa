require "json"
metadata = JSON.parse(File.read(File.join(__dir__, "plugin.json")))
Pod::Spec.new do |spec|
  spec.name = "Avvessa"
  spec.version = metadata.fetch("version")
  spec.summary = "Framework-independent native JPush integration."
  spec.homepage = "https://github.com/sandroxy/avvessa"
  spec.license = { :type => "Apache-2.0", :file => "LICENSE" }
  spec.author = { "SandroX" => "https://github.com/sandroxy" }
  spec.source = { :git => metadata.fetch("repository"), :tag => spec.version.to_s }
  spec.ios.deployment_target = metadata.fetch("native").fetch("ios").fetch("minimumVersion")
  spec.static_framework = true
  spec.requires_arc = true
  spec.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }
  spec.source_files = "native/ios/Sources/Avvessa/**/*.{h,m}"
  spec.public_header_files = "native/ios/Sources/Avvessa/include/Avvessa.h"
  spec.private_header_files = "native/ios/Sources/Avvessa/AVInternal.h"
  spec.resource_bundles = { "AvvessaPrivacy" => ["native/ios/Sources/Avvessa/Resources/PrivacyInfo.xcprivacy"] }
  spec.frameworks = "UIKit", "UserNotifications", "Network"
  spec.libraries = "z", "resolv"
  spec.dependency "JPush", "= " + metadata.fetch("native").fetch("ios").fetch("jpush")
  spec.dependency "JCore", "= " + metadata.fetch("native").fetch("ios").fetch("jcore")
end
