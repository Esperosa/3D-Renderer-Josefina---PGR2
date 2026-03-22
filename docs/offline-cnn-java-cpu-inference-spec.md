# Java CPU Inference Specification (Production Pilot)

## Scope
Manual CPU implementation in Java, no ML runtime dependencies.

## Tensor Layout
- Inference tensors use NCHW float32.
- Input channels order: beauty.R, beauty.G, beauty.B, albedo.R, albedo.G, albedo.B, normal.X, normal.Y, normal.Z.
- Output channels order: denoised beauty RGB.

## Input Preprocess
- beauty channels: log1p(max(x, 0)).
- albedo channels: unchanged linear [0, 1] guidance values.
- normal channels: unchanged world-space guidance in approximately [-1, 1].

## Convolution Definition
For output channel o at pixel (y, x):
out[o,y,x] = bias[o] + sum_i sum_ky sum_kx weight[o,i,ky,kx] * in[i, y+ky-pad, x+kx-pad]
- Kernel size: 3x3.
- Padding: zero padding, 1 pixel on all sides.
- Stride: 1 except downsample convs with stride 2.

## Activation
- LeakyReLU with negative_slope = 0.1.
- Definition: f(x)=x when x>=0 else 0.1*x.

## Downsampling and Upsampling
- Downsample: stride-2 convolution.
- Upsample: bilinear 2x (align_corners=false behavior), then 3x3 conv.

## Skip Connections
- U-Net skip merge uses channel concat, then 3x3 conv fusion.
- Residual blocks: x + conv2(leaky_relu(conv1(x))) then LeakyReLU.
- Final residual output: output = beauty_log_space + residual_pred.

## Output Conversion
- Predicted output is in log-compressed domain.
- Convert back to linear HDR with expm1(max(x, 0)).
- Clamp only at file format/output stage if needed, not inside model math.

## Tiled Inference
- Use tile size T and overlap O (O < T/2).
- Step = T - 2*O.
- Blend with separable feather window multiplied in XY.
- Accumulate weighted sum and divide by weight sum per pixel.

## Weight Files
- Format: raw float32 binary per tensor + JSON manifest.
- Endianness: little-endian.
- Conv weights layout: OIHW.
- Manifest path: C:\Users\jirka\Documents\GitHub\3D-Render-Physics\runtime\denoiser-package\java_weights\weights_manifest.json

## Model Summary
- Name: JavaCpuMiniUNet
- Parameters: 340595
- Receptive field estimate: {'rf_h': 69, 'rf_w': 69}

## Java Implementation Checklist
1. Load all tensors from manifest files as float32 arrays.
2. Implement NCHW conv kernel with zero padding and stride 1/2.
3. Implement bilinear upsample (align_corners=false equivalent).
4. Implement LeakyReLU and residual adds exactly.
5. Implement tiled inference blend to avoid seams.
6. Verify parity with a fixed reference output on one fixed EXR sample.
