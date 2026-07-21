#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

typedef void (^AudioTapCallback)(const float *pcmData, int numFrames, int numChannels, int sampleRate);

/**
 * Thin Objective-C bridge over AVPlayer and AVPlayerLayer.
 *
 * Rationale: against the Xcode 26.5 SDK, Kotlin/Native's cinterop fails to
 * merge AVPlayer's and AVPlayerLayer's Objective-C category / property methods
 * (play/pause/rate/seekToTime/addPeriodicTimeObserver..., setPlayer:, etc.)
 * into the generated Kotlin classes, so those calls are unresolvable from
 * Kotlin. This wrapper re-exposes the calls needed by the player composable as
 * instance methods on a plain NSObject subclass, whose methods cinterop merges
 * correctly. It is a toolchain workaround, not application logic — keep it
 * minimal.
 */
@interface AVPlayerBridge : NSObject

@property (nonatomic, strong, readonly) AVPlayer *player;
@property (nonatomic, strong, readonly) AVPlayerLayer *playerLayer;

// Configures the shared AVAudioSession for `.playback` (background audio).
// Returns NO on failure. Lives here for the same category-merge reason as the
// rest of this class: AVAudioSession's setCategory/setActive are declared in
// AVFAudio categories that cinterop fails to merge onto the generated class.
+ (BOOL)configurePlaybackSession;

- (instancetype)initWithURL:(NSURL *)url;

// Transport
- (void)play;
- (void)pause;
- (float)rate;
- (void)setMuted:(BOOL)muted;

// Time / seeking (CMTime values)
- (CMTime)duration;
- (void)seekToTime:(CMTime)time;

// Periodic time observation (pass nil for queue to use the main queue).
- (id)addPeriodicTimeObserverForInterval:(CMTime)interval
                                   queue:(dispatch_queue_t)queue
                              usingBlock:(void (^)(CMTime time))block;
- (void)removeTimeObserver:(id)observer;

// Item lifecycle
- (void)replaceCurrentItemWithItem:(AVPlayerItem *)item;

// Creates a UIView whose backing layer is an AVPlayerLayer linked to this player.
// It automatically resizes the player layer to match its bounds.
- (UIView *)createPlayerView;

// Intercepts audio PCM data
- (void)installAudioTapWithCallback:(AudioTapCallback)callback;

// Quality / Rendition controls
@property (nonatomic, assign) double preferredPeakBitRate;
- (void)setVideoEnabled:(BOOL)enabled;

// Metrics
- (CGSize)videoSize;
- (CMTime)bufferedDuration;

@end
