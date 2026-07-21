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

- (void)layoutInSuperview:(UIView *)superview {
    if (self.playerLayer.superlayer == nil) {
        [superview.layer addSublayer:self.playerLayer];
    }
    self.playerLayer.frame = superview.bounds;
    // CMP's UIKitView renders the native UIView *above* the Compose layer tree,
    // which would put the video on top of the control overlays (slider, buttons).
    // Lowering the host view's layer zPosition to a negative value places the
    // entire native view (player sublayer included) below the Compose surface, so
    // the Compose-drawn controls render on top and stay tappable.
    superview.layer.zPosition = -1.0f;
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

@end
