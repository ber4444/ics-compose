#import "AVPlayerBridge.h"
#import <AVFAudio/AVFAudio.h>

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
}

@end
