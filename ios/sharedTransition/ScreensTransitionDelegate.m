#import <RNReanimated/ScreensTransitionDelegate.h>

@implementation ScreensTransitionDelegate

@synthesize sharedTransitionsItems;

- (instancetype)init
{
  self = [super init];
  sharedTransitionsItems = [NSMutableDictionary<NSString *, NSMutableArray<NSNumber *> *> new];
  return self;
}

- (void)reanimatedMockTransitionWithConverterView:(UIView *)converter
                                         fromView:(UIView *)fromView
                                fromViewConverter:(UIView *)startingViewConverter
                                           toView:(UIView *)toView
                                  toViewConverter:(UIView *)toViewConverter
                                   transitionType:(NSString *)transitionType
{
  REASnapshot *before = [[REASnapshot alloc] init:fromView withConverter:converter withParent:startingViewConverter];
  if ([transitionType isEqualToString:@"sharedElementTransition"]) {
    REASnapshot *after = [[REASnapshot alloc] init:toView withConverter:converter withParent:toViewConverter];
    [_animationsManager onViewTransition:toView before:before after:after];
  } else {
    [_animationsManager onScreenTransition:fromView finish:before transitionType:transitionType];
  }
}

- (void)registerTransitioinTag:(NSString *)transitionTag viewTag:(NSNumber *)viewTag
{
  if (!sharedTransitionsItems[transitionTag]) {
    sharedTransitionsItems[transitionTag] = [NSMutableArray<NSNumber *> new];
  }
  [sharedTransitionsItems[transitionTag] addObject:viewTag];
}

- (void)unregisterTransitioinTag:(NSString *)transitionTag viewTag:(NSNumber *)viewTag
{
  [sharedTransitionsItems[transitionTag] removeObject:viewTag];
  if ([sharedTransitionsItems[transitionTag] count] == 0) {
    sharedTransitionsItems[transitionTag] = Nil;
  }
}

@end