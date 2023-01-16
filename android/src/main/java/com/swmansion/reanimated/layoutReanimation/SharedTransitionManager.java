package com.swmansion.reanimated.layoutReanimation;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ViewGroupManager;
import com.facebook.react.views.view.ReactViewGroup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SharedTransitionManager {
  private final AnimationsManager mAnimationsManager;
  private NativeMethodsHolder mNativeMethodsHolder;
  private final List<View> mAddedSharedViews = new ArrayList<>();
  private final Map<Integer, View> mSharedTransitionParent = new HashMap<>();
  private final Map<Integer, Integer> mSharedTransitionInParentIndex = new HashMap<>();
  private boolean mIsSharedTransitionActive;
  private final Map<Integer, Snapshot> mSnapshotRegistry = new HashMap<>();
  private final List<View> mCurrentSharedTransitionViews = new ArrayList<>();
  private View mTransitionContainer;
  private final List<View> mRemovedSharedViews = new ArrayList<>();

  public SharedTransitionManager(AnimationsManager animationsManager) {
    mAnimationsManager = animationsManager;
  }

  protected void notifyAboutNewView(View view) {
    mAddedSharedViews.add(view);
  }

  protected void notifyAboutRemovedView(View view) {
    mRemovedSharedViews.add(view);
  }

  protected List<View> getCurrentSharedTransitionViews() {
    return mCurrentSharedTransitionViews;
  }

  protected void viewsDidLayout() {
    setupSharedTransitionForViews(mAddedSharedViews, true);
    mAddedSharedViews.clear();
  }

  protected void viewsDidRemoved() {
    setupSharedTransitionForViews(mRemovedSharedViews, false);
    for (View removedSharedView : mRemovedSharedViews) {
      visitTree(removedSharedView.getId(), true);
    }
    mRemovedSharedViews.clear();
  }

  protected void setNativeMethods(NativeMethodsHolder nativeMethods) {
    mNativeMethodsHolder = nativeMethods;
  }

  private void setupSharedTransitionForViews(List<View> sharedViews, boolean withNewElements) {
    if (sharedViews.isEmpty()) {
      return;
    }
    sortViewsByTags(sharedViews);
    List<SharedElement> sharedElements =
        getSharedElementForCurrentTransition(sharedViews, withNewElements);
    if (sharedElements.isEmpty()) {
      return;
    }
    setupTransitionContainer();
    reparentSharedViewsForCurrentTransition(sharedElements);
    startSharedTransition(sharedElements);
  }

  private void sortViewsByTags(List<View> views) {
    Collections.sort(views, (v1, v2) -> Integer.compare(v2.getId(), v1.getId()));
  }

  private List<SharedElement> getSharedElementForCurrentTransition(
      List<View> sharedViews, boolean addedNewScreen) {
    Set<Integer> viewTags = new HashSet<>();
    if (!addedNewScreen) {
      for (View view : sharedViews) {
        viewTags.add(view.getId());
      }
    }
    List<SharedElement> sharedElements = new ArrayList<>();
    ReanimatedNativeHierarchyManager reanimatedNativeHierarchyManager =
        mAnimationsManager.getReanimatedNativeHierarchyManager();
    for (View sharedView : sharedViews) {
      int targetViewTag = mNativeMethodsHolder.findTheOtherForSharedTransition(sharedView.getId());
      boolean bothAreRemoved = !addedNewScreen && viewTags.contains(targetViewTag);
      if (targetViewTag < 0) {
        continue;
      }
      View viewSource, viewTarget;
      if (addedNewScreen) {
        viewSource = reanimatedNativeHierarchyManager.resolveView(targetViewTag);
        viewTarget = sharedView;
      } else {
        viewSource = sharedView;
        viewTarget = reanimatedNativeHierarchyManager.resolveView(targetViewTag);
      }
      if (bothAreRemoved) {
        // case for nested stack
        clearAllSharedConfigsForView(viewSource);
        clearAllSharedConfigsForView(viewTarget);
        continue;
      }

      View viewSourceScreen = findScreen(viewSource);
      View viewTargetScreen = findScreen(viewTarget);
      if (viewSourceScreen == null || viewTargetScreen == null) {
        continue;
      }

      ViewGroup stack = (ViewGroup) findStack(viewSourceScreen);
      if (stack == null) {
        continue;
      }

      ViewGroupManager stackViewGroupManager =
          (ViewGroupManager) reanimatedNativeHierarchyManager.resolveViewManager(stack.getId());
      int screensCount = stackViewGroupManager.getChildCount(stack);

      if (screensCount - 2 < 0) {
        continue;
      }

      View topScreen = stackViewGroupManager.getChildAt(stack, screensCount - 1);
      View secondScreen = stackViewGroupManager.getChildAt(stack, screensCount - 2);
      boolean isRightConfiguration;
      if (addedNewScreen) {
        isRightConfiguration =
            secondScreen.getId() == viewSourceScreen.getId()
                && topScreen.getId() == viewTargetScreen.getId();
      } else {
        isRightConfiguration =
            topScreen.getId() == viewSourceScreen.getId()
                && secondScreen.getId() == viewTargetScreen.getId();
      }
      if (!isRightConfiguration) {
        continue;
      }

      if (addedNewScreen) {
        makeSnapshot(viewSource);
        makeSnapshot(viewTarget);
      }
      Snapshot sourceViewSnapshot = mSnapshotRegistry.get(viewSource.getId());
      Snapshot targetViewSnapshot = mSnapshotRegistry.get(viewTarget.getId());

      if (!mCurrentSharedTransitionViews.contains(viewSource)) {
        mCurrentSharedTransitionViews.add(viewSource);
      }
      if (!mCurrentSharedTransitionViews.contains(viewTarget)) {
        mCurrentSharedTransitionViews.add(viewTarget);
      }
      SharedElement sharedElement =
          new SharedElement(viewSource, sourceViewSnapshot, viewTarget, targetViewSnapshot);
      sharedElements.add(sharedElement);
    }
    return sharedElements;
  }

  private void setupTransitionContainer() {
    if (!mIsSharedTransitionActive) {
      mIsSharedTransitionActive = true;
      ReactContext context = mAnimationsManager.getContext();
      ViewGroup mainWindow =
          (ViewGroup) context.getCurrentActivity().getWindow().getDecorView().getRootView();
      if (mTransitionContainer == null) {
        mTransitionContainer = new ReactViewGroup(context);
      }
      mainWindow.addView(mTransitionContainer);
      mTransitionContainer.bringToFront();
    }
  }

  private void reparentSharedViewsForCurrentTransition(List<SharedElement> sharedElements) {
    for (SharedElement sharedElement : sharedElements) {
      View viewSource = sharedElement.sourceView;
      View viewTarget = sharedElement.targetView;

      if (!mSharedTransitionParent.containsKey(viewSource.getId())) {
        mSharedTransitionParent.put(viewSource.getId(), (View) viewSource.getParent());
        mSharedTransitionInParentIndex.put(
            viewSource.getId(), ((ViewGroup) viewSource.getParent()).indexOfChild(viewSource));
        ((ViewGroup) viewSource.getParent()).removeView(viewSource);
        ((ViewGroup) mTransitionContainer).addView(viewSource);
      }

      if (!mSharedTransitionParent.containsKey(viewTarget.getId())) {
        mSharedTransitionParent.put(viewTarget.getId(), (View) viewTarget.getParent());
        mSharedTransitionInParentIndex.put(
            viewTarget.getId(), ((ViewGroup) viewTarget.getParent()).indexOfChild(viewTarget));
        ((ViewGroup) viewTarget.getParent()).removeView(viewTarget);
        ((ViewGroup) mTransitionContainer).addView(viewTarget);
      }
    }
  }

  private void startSharedTransition(List<SharedElement> sharedElements) {
    for (SharedElement sharedElement : sharedElements) {
      onViewTransition(
          sharedElement.sourceView,
          sharedElement.sourceViewSnapshot,
          sharedElement.targetViewSnapshot);
      onViewTransition(
          sharedElement.targetView,
          sharedElement.sourceViewSnapshot,
          sharedElement.targetViewSnapshot);
    }
  }

  private void onViewTransition(View view, Snapshot before, Snapshot after) {
    HashMap<String, Object> targetValues = after.toTargetMap();
    HashMap<String, Object> startValues = before.toCurrentMap();

    HashMap<String, Float> preparedStartValues =
        mAnimationsManager.prepareDataForAnimationWorklet(startValues, false);
    HashMap<String, Float> preparedTargetValues =
        mAnimationsManager.prepareDataForAnimationWorklet(targetValues, true);
    HashMap<String, Float> preparedValues = new HashMap<>(preparedTargetValues);
    preparedValues.putAll(preparedStartValues);

    mNativeMethodsHolder.startAnimation(view.getId(), "sharedElementTransition", preparedValues);
  }

  protected void finishSharedAnimation(int tag) {
    View view = null;
    for (View sharedView : mCurrentSharedTransitionViews) {
      if (sharedView.getId() == tag) {
        view = sharedView;
        break;
      }
    }
    if (view != null) {
      ((ViewGroup) mTransitionContainer).removeView(view);
      View parentView = mSharedTransitionParent.get(view.getId());
      int childIndex = mSharedTransitionInParentIndex.get(view.getId());
      ViewGroup parentViewGroup = ((ViewGroup) parentView);
      if (childIndex <= parentViewGroup.getChildCount()) {
        parentViewGroup.addView(view, childIndex);
      } else {
        parentViewGroup.addView(view);
      }
      Snapshot viewSourcePreviousSnapshot = mSnapshotRegistry.get(view.getId());
      int originY = viewSourcePreviousSnapshot.originY;
      if (findStack(view) == null) {
        viewSourcePreviousSnapshot.originY = viewSourcePreviousSnapshot.originYByParent;
      }
      Map<String, Object> snapshotMap = viewSourcePreviousSnapshot.toBasicMap();
      Map<String, Object> preparedValues = new HashMap<>();
      for (String key : snapshotMap.keySet()) {
        Object value = snapshotMap.get(key);
        preparedValues.put(key, (double) PixelUtil.toDIPFromPixel((int) value));
      }
      mAnimationsManager.progressLayoutAnimation(view.getId(), preparedValues, true);
      viewSourcePreviousSnapshot.originY = originY;
      mCurrentSharedTransitionViews.remove(view);
    }
    if (mCurrentSharedTransitionViews.isEmpty()) {
      mSharedTransitionParent.clear();
      mSharedTransitionInParentIndex.clear();
      if (mTransitionContainer != null) {
        ViewParent transitionContainerParent = mTransitionContainer.getParent();
        if (transitionContainerParent != null) {
          ((ViewGroup) transitionContainerParent).removeView(mTransitionContainer);
        }
      }
      mCurrentSharedTransitionViews.clear();
      mIsSharedTransitionActive = false;
    }
  }

  private View findScreen(View view) {
    ViewParent parent = view.getParent();
    while (parent != null) {
      if (parent.getClass().getSimpleName().equals("Screen")) {
        return (View) parent;
      }
      parent = parent.getParent();
    }
    return null;
  }

  private View findStack(View view) {
    ViewParent parent = view.getParent();
    while (parent != null) {
      if (parent.getClass().getSimpleName().equals("ScreenStack")) {
        return (View) parent;
      }
      parent = parent.getParent();
    }
    return null;
  }

  protected void makeSnapshot(View view) {
    Snapshot snapshot = new Snapshot(view);
    View screen = findScreen(view);
    if (screen != null) {
      snapshot.originY -= screen.getTop();
    }
    mSnapshotRegistry.put(view.getId(), snapshot);
  }

  protected void visitTreeForTags(int[] viewTags, boolean clearConfig) {
    if (viewTags == null) {
      return;
    }
    for (int viewTag : viewTags) {
      visitTree(viewTag, clearConfig);
    }
  }

  private void visitTree(int tag, boolean clearConfig) {
    if (tag == -1) {
      return;
    }
    ViewGroup viewGroup;
    ViewGroupManager<ViewGroup> viewGroupManager;
    ReanimatedNativeHierarchyManager reanimatedNativeHierarchyManager =
        mAnimationsManager.getReanimatedNativeHierarchyManager();
    try {
      View view = reanimatedNativeHierarchyManager.resolveView(tag);
      if (!clearConfig && mAnimationsManager.hasAnimationForTag(tag, "sharedElementTransition")) {
        mRemovedSharedViews.add(view);
        makeSnapshot(view);
      }
      if (clearConfig) {
        mNativeMethodsHolder.clearAnimationConfig(tag);
      }

      if (!(view instanceof ViewGroup)) {
        return;
      }
      viewGroup = (ViewGroup) view;
      viewGroupManager =
          (ViewGroupManager) reanimatedNativeHierarchyManager.resolveViewManager(tag);
    } catch (IllegalViewOperationException e) {
      return;
    }
    if (viewGroupManager == null) {
      return;
    }
    for (int i = 0; i < viewGroupManager.getChildCount(viewGroup); i++) {
      View child = viewGroupManager.getChildAt(viewGroup, i);
      visitTree(child.getId(), clearConfig);
    }
  }

  void visitTreeAndMakeSnapshot(View view) {
    if (!(view instanceof ViewGroup)) {
      return;
    }
    ViewGroup viewGroup = (ViewGroup) view;
    for (int i = 0; i < viewGroup.getChildCount(); i++) {
      View child = viewGroup.getChildAt(i);
      visitTreeAndMakeSnapshot(child);
    }
  }

  private void clearAllSharedConfigsForView(View view) {
    int viewTag = view.getId();
    mSnapshotRegistry.remove(viewTag);
    mNativeMethodsHolder.clearAnimationConfig(viewTag);
  }
}
