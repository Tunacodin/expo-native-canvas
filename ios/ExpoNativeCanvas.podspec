Pod::Spec.new do |s|
  s.name           = 'ExpoNativeCanvas'
  s.version        = '0.1.0'
  s.summary        = 'Native drawing canvas — pen, image background, pan, pinch.'
  s.description    = 'UI-thread only drawing canvas. No JS bridge per touch event.'
  s.author         = { 'Tunacodin' => '' }
  s.homepage       = 'https://github.com/Tunacodin/expo-native-canvas'
  s.license        = { :type => 'MIT', :file => '../LICENSE' }
  s.platforms      = { :ios => '13.4', :tvos => '13.4' }
  s.source         = { git: 'https://github.com/Tunacodin/expo-native-canvas.git' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }

  s.source_files = "**/*.{h,m,swift}"
end
