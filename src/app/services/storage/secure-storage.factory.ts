import { Platform } from '@ionic/angular'

import { SecureStorageServiceMock } from './secure-storage.mock'
import { SecureStorageService } from './storage.service'

export function SecureStorageFactory(platform: Platform) {
  if (platform.is('cordova')) {
    return new SecureStorageService()
  } else {
    return new SecureStorageServiceMock()
  }
}
