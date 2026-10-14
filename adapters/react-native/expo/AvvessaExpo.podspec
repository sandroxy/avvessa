require "json"
package = JSON.parse(File.read(File.join(__dir__, "../package.json")))
Pod::Spec.new do |spec|
  spec.name = "AvvessaExpo"
  spec.version = package.fetch("version")
  spec.summary = "Expo lifecycle forwarding for Avvessa"
  spec.homepage = package.fetch("homepage")
  spec.license = { :type => "Apache-2.0", :file => "../LICENSE" }
  spec.authors = { "SandroX" => "sandroxy" }
  spec.source = { :git => package.fetch("repository").fetch("url"), :tag => spec.version.to_s }
  spec.platform = :ios, "15.1"
  spec.swift_version = "5.9"
  spec.static_framework = true
  spec.source_files = "*.swift"
  spec.dependency "ExpoModulesCore"
  spec.dependency "AvvessaReactNative", "= #{package.fetch('version')}"
end
