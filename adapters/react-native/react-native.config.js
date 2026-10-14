const path = require('node:path');
module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: 'android',
        packageImportPath: 'import com.sandrox.avvessa.reactnative.AvvessaPackage;',
        packageInstance: 'new AvvessaPackage()',
      },
      ios: { podspecPath: path.join(__dirname, 'AvvessaReactNative.podspec') },
    },
  },
};
