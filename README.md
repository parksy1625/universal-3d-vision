# Universal 3D Vision

Galaxy S23 real-time ARCore depth-to-3D point cloud prototype.

## Build APK
Open [GitHub Actions](../../actions/workflows/android-build.yml) and download the `u3dv-debug-apk` artifact from a successful run. Debug APK is not a Play Store release.

## Current experimental features
- ARCore Raw Depth -> point cloud, with tracking pose transformed into a persistent local world coordinate system
- Camera YUV color sampling for each point (**approximate**: exact image/depth alignment not yet calibrated)
- One-finger drag to orbit the 3D view
- Two-finger pinch to zoom
- 2.5 cm voxel de-duplication and bounded 80,000 point map

## Galaxy S23 test protocol
1. Install the debug APK and grant camera permission.
2. Point the phone at a well-lit textured desk. Move slowly to allow ARCore tracking.
3. Drag to rotate and pinch to zoom. Check whether depth is visible.
4. Move sideways and return. Check whether old points remain in roughly the same world positions.
5. Check color alignment, jitter, drift, heat, and whether the app remains responsive.

## Known limitations
- Camera YUV image and depth pixels are sampled by proportional image scaling, **not calibrated reprojection**. Color may be rotated or displaced.
- The point cloud is bounded; old voxels are discarded as new ones arrive.
- This is an experimental implementation, not yet verified on an actual S23.
- No mesh export or multiple camera fusion yet.
