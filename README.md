# Universal 3D Vision

Galaxy S23 ARCore real-time depth point-cloud prototype.

## Build
GitHub Actions → Android APK → Run workflow, then download `u3dv-debug-apk` artifact. Install on an ARCore-supported phone with Google Play Services for AR.

## v0.1
- ARCore RAW_DEPTH_ONLY session
- GL point-cloud visualization using camera depth and pose
- Touch-drag to orbit view (next milestone)

The point cloud is monochrome in this initial prototype. RGB alignment, accumulation, mesh and multi-camera support are future milestones.
