#import "AVPlayerBridge.h"
#import <AVFAudio/AVFAudio.h>
#import <CoreMedia/CoreMedia.h>
#import <MediaToolbox/MediaToolbox.h>

@interface AVPlayerBridge ()
@property (nonatomic, copy) AudioTapCallback tapCallback;
@end

typedef struct {
    __unsafe_unretained AVPlayerBridge *bridge;
    Float64 sampleRate;
    int channels;
} TapContext;

static void tapInit(MTAudioProcessingTapRef tap, void *clientInfo, void **tapStorageOut) {
    TapContext *context = calloc(1, sizeof(TapContext));
    context->bridge = (__bridge AVPlayerBridge *)clientInfo;
    *tapStorageOut = context;
}

static void tapFinalize(MTAudioProcessingTapRef tap) {
    TapContext *context = (TapContext *)MTAudioProcessingTapGetStorage(tap);
    free(context);
}

static void tapPrepare(MTAudioProcessingTapRef tap, CMItemCount maxFrames, const AudioStreamBasicDescription *processingFormat) {
    TapContext *context = (TapContext *)MTAudioProcessingTapGetStorage(tap);
    context->sampleRate = processingFormat->mSampleRate;
    context->channels = processingFormat->mChannelsPerFrame;
}

static void tapUnprepare(MTAudioProcessingTapRef tap) {
}

static void tapProcess(MTAudioProcessingTapRef tap, CMItemCount numberFrames, MTAudioProcessingTapFlags flags, AudioBufferList *bufferListInOut, CMItemCount *numberFramesOut, MTAudioProcessingTapFlags *flagsOut) {
    TapContext *context = (TapContext *)MTAudioProcessingTapGetStorage(tap);
    
    OSStatus status = MTAudioProcessingTapGetSourceAudio(tap, numberFrames, bufferListInOut, flagsOut, NULL, numberFramesOut);
    if (status != noErr) return;
    
    if (context->bridge.tapCallback && bufferListInOut->mNumberBuffers > 0) {
        AudioBuffer *buffer = &bufferListInOut->mBuffers[0];
        const float *pcmData = (const float *)buffer->mData;
        if (pcmData) {
            context->bridge.tapCallback(pcmData, (int)*numberFramesOut, context->channels, (int)context->sampleRate);
        }
    }
}

@interface AVPlayerBridgeView : UIView
@property (nonatomic, strong) AVPlayer *player;
@end

@implementation AVPlayerBridgeView
+ (Class)layerClass {
    return [AVPlayerLayer class];
}
- (AVPlayer *)player {
    return [(AVPlayerLayer *)self.layer player];
}
- (void)setPlayer:(AVPlayer *)player {
    [(AVPlayerLayer *)self.layer setPlayer:player];
    [(AVPlayerLayer *)self.layer setVideoGravity:AVLayerVideoGravityResizeAspect];
}
@end

@implementation AVPlayerBridge

+ (BOOL)configurePlaybackSession {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    [session setCategory:AVAudioSessionCategoryPlayback
                    mode:AVAudioSessionModeDefault
                 options:0
                   error:&error];
    if (error != nil) {
        return NO;
    }
    [session setActive:YES error:&error];
    return error == nil;
}

- (instancetype)initWithURL:(NSURL *)url {
    self = [super init];
    if (self) {
        _player = [[AVPlayer alloc] initWithURL:url];
        _playerLayer = [AVPlayerLayer playerLayerWithPlayer:_player];
        _playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    }
    return self;
}

- (void)play { [self.player play]; }
- (void)pause { [self.player pause]; }
- (float)rate { return [self.player rate]; }
- (void)setMuted:(BOOL)muted { [self.player setMuted:muted]; }

- (CMTime)duration {
    AVPlayerItem *item = self.player.currentItem;
    return item ? item.duration : kCMTimeInvalid;
}

- (void)seekToTime:(CMTime)time { [self.player seekToTime:time]; }

- (id)addPeriodicTimeObserverForInterval:(CMTime)interval
                                   queue:(dispatch_queue_t)queue
                              usingBlock:(void (^)(CMTime time))block {
    return [self.player addPeriodicTimeObserverForInterval:interval
                                                     queue:queue
                                                usingBlock:block];
}

- (void)removeTimeObserver:(id)observer {
    [self.player removeTimeObserver:observer];
}

- (void)replaceCurrentItemWithItem:(AVPlayerItem *)item {
    [self.player replaceCurrentItemWithPlayerItem:item];
}

- (UIView *)createPlayerView {
    AVPlayerBridgeView *view = [[AVPlayerBridgeView alloc] initWithFrame:CGRectZero];
    view.player = self.player;
    return view;
}

- (void)installAudioTapWithCallback:(AudioTapCallback)callback {
    self.tapCallback = callback;
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return;

    AVPlayerItemTrack *audioTrack = nil;
    for (AVPlayerItemTrack *track in item.tracks) {
        if ([track.assetTrack.mediaType isEqualToString:AVMediaTypeAudio]) {
            audioTrack = track;
            break;
        }
    }
    if (!audioTrack) return;

    MTAudioProcessingTapCallbacks callbacks;
    callbacks.version = kMTAudioProcessingTapCallbacksVersion_0;
    callbacks.clientInfo = (__bridge void *)self;
    callbacks.init = tapInit;
    callbacks.prepare = tapPrepare;
    callbacks.process = tapProcess;
    callbacks.unprepare = tapUnprepare;
    callbacks.finalize = tapFinalize;

    MTAudioProcessingTapRef tap;
    OSStatus status = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap);
    if (status != noErr) return;

    AVMutableAudioMixInputParameters *params = [AVMutableAudioMixInputParameters audioMixInputParametersWithTrack:audioTrack.assetTrack];
    params.audioTapProcessor = tap;
    
    AVMutableAudioMix *audioMix = [AVMutableAudioMix audioMix];
    audioMix.inputParameters = @[params];
    item.audioMix = audioMix;

    CFRelease(tap);
}

// Quality / Rendition controls

- (double)preferredPeakBitRate {
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return 0.0;
    return item.preferredPeakBitRate;
}

- (void)setPreferredPeakBitRate:(double)preferredPeakBitRate {
    AVPlayerItem *item = self.player.currentItem;
    if (item) {
        item.preferredPeakBitRate = preferredPeakBitRate;
    }
}

- (void)setVideoEnabled:(BOOL)enabled {
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return;
    for (AVPlayerItemTrack *track in item.tracks) {
        if ([track.assetTrack.mediaType isEqualToString:AVMediaTypeVideo]) {
            track.enabled = enabled;
        }
    }
}

// Metrics

- (CGSize)videoSize {
    AVPlayerItem *item = self.player.currentItem;
    if (!item) return CGSizeZero;
    return item.presentationSize;
}

- (CMTime)bufferedDuration {
    AVPlayerItem *item = self.player.currentItem;
    if (!item || item.loadedTimeRanges.count == 0) return kCMTimeZero;
    CMTimeRange timeRange = [item.loadedTimeRanges.firstObject CMTimeRangeValue];
    return CMTimeAdd(timeRange.start, timeRange.duration);
}

@end
