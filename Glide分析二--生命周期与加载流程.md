#Glide分析二：生命周期与加载流程
>推荐郭霖的博客---关于 Glide 系列8篇，由浅入深，v3->v4 都有介绍。本篇仅为结合源码的读书笔记，以 Glide-v3.7 源码为基准。目前：Glide 功能强大且最稳定版本还是 v3.7。

>[https://blog.csdn.net/guolin_blog/article/details/53759439]()

>[https://blog.csdn.net/guolin_blog/article/details/53939176]()

>[https://blog.csdn.net/guolin_blog/article/details/54895665]()

>[https://blog.csdn.net/guolin_blog/article/details/70215985]()

>[https://blog.csdn.net/guolin_blog/article/details/71524668]()

>[https://blog.csdn.net/guolin_blog/article/details/78179422]()

>[https://blog.csdn.net/guolin_blog/article/details/78357251]()

>[https://blog.csdn.net/guolin_blog/article/details/78582548]()


##2 Glide 的执行流程
由于第一篇介绍的是 Glide 的基础用法，就跳过了。本章节从下面一行最关键的代码开始。

```java
Glide.with(this).load(url).into(imageView);
```

###2-1、 如何绑定生命周期

首先看下 Glide 提供的一组静态方法，通过 with() 传入不同类型的 context。（备注：两个相同的fragment一个是V4包提供的）。

![Glide-with方法重载](/Users/zhufan/Documents/markdown/android随笔/Glide分析/Glide-with方法重载.png)

所以 Glide 能够感知生命周期就是与传入的具体 context 的生命周期强相关联。我们看下具体的方法内容：

```java
public class Glide {

    ...

    public static RequestManager with(Context context) {
        RequestManagerRetriever retriever = RequestManagerRetriever.get();
        return retriever.get(context);
    }

    public static RequestManager with(Activity activity) {
        RequestManagerRetriever retriever = RequestManagerRetriever.get();
        return retriever.get(activity);
    }

    public static RequestManager with(FragmentActivity activity) {
        RequestManagerRetriever retriever = RequestManagerRetriever.get();
        return retriever.get(activity);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static RequestManager with(android.app.Fragment fragment) {
        RequestManagerRetriever retriever = RequestManagerRetriever.get();
        return retriever.get(fragment);
    }

    public static RequestManager with(Fragment fragment) {
        RequestManagerRetriever retriever = RequestManagerRetriever.get();
        return retriever.get(fragment);
    }
}

```
<br>
RequestManagerRetriever.get() 方法就是一个单例方法，获取到 RequestManagerRetriever 实例后，调用相应的 get(xxx) 方法获取到一个 RequestManager 对象。

```java
public class RequestManagerRetriever ... {
 
 	... 
 	
	/**
	 * Retrieves and returns the RequestManagerRetriever singleton.
	 */
	public static RequestManagerRetriever get() {
	    return INSTANCE;
	}
	
	...
		
	public RequestManager get(Context context) {
	    if (context == null) {
	        throw new IllegalArgumentException("You cannot start a load on a null Context");
	    } else if (Util.isOnMainThread() && !(context instanceof Application)) {
	        if (context instanceof FragmentActivity) {
	            return get((FragmentActivity) context);
	        } else if (context instanceof Activity) {
	            return get((Activity) context);
	        } else if (context instanceof ContextWrapper) {
	            return get(((ContextWrapper) context).getBaseContext());
	        }
	    }
	    return getApplicationManager(context);
	}
	
}
```
根据传入的 context 首先判断非空，然后判别类型，调用具体重载的 get(xxx) 方法返回 **RequestManger** 对象。
> context 类型判断，顺序判断
> 
>* FragmentActivity
>* Activity
>* ContextWrapper 包装类，拆解里面具体的 baseContext
>* 其他类型其实就是 ApplicationContext 类型。

####2-1-1
 如果传入的是 Application 对象，那么 Glide 并不会做特殊处理，会自动与应用的生命周期同步，应用关闭则 Glide 加载也会停止。**new ApplicationLifecycle()** 

```java
public class RequestManagerRetriever ... {

	...

    private RequestManager getApplicationManager(Context context) {
	    // Either an application context or we're on a background thread.
	    if (applicationManager == null) {
	        synchronized (this) {
	            if (applicationManager == null) {
	                // Normally pause/resume is taken care of by the fragment we add to the fragment or activity.
	                // However, in this case since the manager attached to the application will not receive lifecycle
	                // events, we must force the manager to start resumed using ApplicationLifecycle.
	                applicationManager = new RequestManager(context.getApplicationContext(),
	                        new ApplicationLifecycle(), new EmptyRequestManagerTreeNode());
	            }
	        }
	    }
	
	    return applicationManager;
	}
}
```


```java
class ApplicationLifecycle implements Lifecycle {
    @Override
    public void addListener(LifecycleListener listener) {
        listener.onStart();
    }
}
```

####2-1-2
如果传入的是非 ApplicationContext 类型，Glide 会向当前的 Activity 中，添加一个隐藏的 Fragment。这样 Glide 加载图片的生命周期就与所依附的 activity 生命周期绑定起来了。下面我们以 get(FragmentActivity) 为例，具体分析啊下。

```java
public class RequestManagerRetriever ... {

	...

    public RequestManager get(FragmentActivity activity) {
        if (Util.isOnBackgroundThread()) {
            return get(activity.getApplicationContext());
        } else {
            assertNotDestroyed(activity);
            FragmentManager fm = activity.getSupportFragmentManager();
            return supportFragmentGet(activity, fm);
        }
    }
    
    RequestManager supportFragmentGet(Context context, FragmentManager fm) {
        SupportRequestManagerFragment current = getSupportRequestManagerFragment(fm);
        RequestManager requestManager = current.getRequestManager();
        if (requestManager == null) {
            requestManager = new RequestManager(context, current.getLifecycle(), current.getRequestManagerTreeNode());
            current.setRequestManager(requestManager);
        }
        return requestManager;
    }
    
    SupportRequestManagerFragment getSupportRequestManagerFragment(final FragmentManager fm) {
        SupportRequestManagerFragment current = (SupportRequestManagerFragment) fm.findFragmentByTag(FRAGMENT_TAG);
        if (current == null) {
            current = pendingSupportRequestManagerFragments.get(fm);
            if (current == null) {
                current = new SupportRequestManagerFragment();
                pendingSupportRequestManagerFragments.put(fm, current);
                fm.beginTransaction().add(current, FRAGMENT_TAG).commitAllowingStateLoss();
                handler.obtainMessage(ID_REMOVE_SUPPORT_FRAGMENT_MANAGER, fm).sendToTarget();
            }
        }
        return current;
    }
    
}
```
**代码简读下：**

* 第 10 行通过 activity 取到 FragmentManger

* 第 11 行返回通过第 15 行 supportFragmentGet() 方法得到的 RequestManager 对象

* 第 25 行的 getSupportRequestManagerFragment（）方法，将隐藏的 fragment 添加到 Activity 里。


<br>
**问：为什么是隐藏的 fragment 呢？**

答：第 32 行，仔细看添加 fragment 使用的方法。

>* public abstract FragmentTransaction add(Fragment fragment, String tag); 

> 对比

>* public abstract FragmentTransaction add(@IdRes int containerViewId, Fragment fragment, String tag);

<br>
**问：pendingSupportRequestManagerFragments 这个是什么 ？**

答：是 Map 类型的集合，用来缓存需要添加到 Activity 中的 Fragment。因为使用的是 commitAllowingStateLoss() 方法，这样在执行添加 Fragment 事务时是个非同步的执行任务。实际使用主线程 handler 来 post() 任务，有兴趣的可以看下 Fragmentmanager 类的 scheduleCommit 方法。

**这里额外再提一句，从第 7 行代码可以看出，如果我们是在非主线程当中使用的 Glide，那么不管你是传入的 Activity 还是 Fragment，都会被强制当成 Application 来处理。**
>
> Glide 源码中涉及很多细节信息，如果每个都要去细究，很容易干扰我们阅读的思路。后面这些细节我们本文不会过多得一一分析了。


####2-1-3
具体生命周期的秘密都在这个 SupportResquestManagerFragment 里，里面有个 实现了生命周期接口的 lifecycle 对象，在收到 Activity 各个生命周期回调时，通过 lifecycle 对象发送生命周期事件。

```java
public class SupportRequestManagerFragment extends Fragment {

    ...
    
    private final ActivityFragmentLifecycle lifecycle;

    ActivityFragmentLifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void onStart() {
        super.onStart();
        lifecycle.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        lifecycle.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycle.onDestroy();
    }

}
```

<br>
总体来说，第一个 with() 方法的源码还是比较好理解的。其实就是为了得到一个**RequestManager**对象而已，然后 Glide 会根据我们传入 with() 方法的参数来确定图片加载的生命周期，并没有什么特别复杂的逻辑。不过复杂的逻辑还在后面等着我们呢，接下来我们开始分析第二步，load() 方法。

<br>
###2-2、 load 方法

####2-2-1 RequestManager.load() -> DrawableTypeRequest 对象
```java
Glide.with(this).load(url).into(imageView);
```
with() 方法返回的是一个 RequestManager 对象，那自然可以从 RequestManager 类中找到我们要的 load() 方法。Glide load() 方法具有多个重载方法，支持图片URL字符串、图片本地路径等形式。我们以 URL 类型具体分析下：

```java
public class RequestManager implements LifecycleListener {

	...
	
	/**
     * Returns a request builder to load the given {@link java.lang.String}.
     * signature.
     *
     * @see #fromString()
     * @see #load(Object)
     *
     * @param string A file path, or a uri or url handled by {@link com.bumptech.glide.load.model.UriLoader}.
     */
    public DrawableTypeRequest<String> load(String string) {
        return (DrawableTypeRequest<String>) fromString().load(string);
    }
    
    /**
     * Returns a request builder that loads data from {@link String}s using an empty signature.
     *
     * <p>
     *     Note - this method caches data using only the given String as the cache key. If the data is a Uri outside of
     *     your control, or you otherwise expect the data represented by the given String to change without the String
     *     identifier changing, Consider using
     *     {@link com.bumptech.glide.GenericRequestBuilder#signature(com.bumptech.glide.load.Key)} to mixin a signature
     *     you create that identifies the data currently at the given String that will invalidate the cache if that data
     *     changes. Alternatively, using {@link com.bumptech.glide.load.engine.DiskCacheStrategy#NONE} and/or
     *     {@link com.bumptech.glide.DrawableRequestBuilder#skipMemoryCache(boolean)} may be appropriate.
     * </p>
     *
     * @see #from(Class)
     * @see #load(String)
     */
    public DrawableTypeRequest<String> fromString() {
        return loadGeneric(String.class);
    }    

    private <T> DrawableTypeRequest<T> loadGeneric(Class<T> modelClass) {
        ModelLoader<T, InputStream> streamModelLoader = Glide.buildStreamModelLoader(modelClass, context);
        ModelLoader<T, ParcelFileDescriptor> fileDescriptorModelLoader =
                Glide.buildFileDescriptorModelLoader(modelClass, context);
        if (modelClass != null && streamModelLoader == null && fileDescriptorModelLoader == null) {
            throw new IllegalArgumentException("Unknown type " + modelClass + ". You must provide a Model of a type for"
                    + " which there is a registered ModelLoader, if you are using a custom model, you must first call"
                    + " Glide#register with a ModelLoaderFactory for your custom model class");
        }

        return optionsApplier.apply(
                new DrawableTypeRequest<T>(modelClass, streamModelLoader, fileDescriptorModelLoader, context,
                        glide, requestTracker, lifecycle, optionsApplier));
    }

} 
```

**代码简读**

* load() 方法根据传入的具体参数类型，构建一个 DrawableTypeRequest 对象。
 
* 第 38 行，loadGeneric() 方法根据 modelClass（这里就是String.class）调用 buildStreamModelLoader() 方法构建一个 streamModelLoader ，buildFileDescriptorModelLoader() 方法构建一个 fileDescriptorModelLoader 对象。这两个都是 ModelLoader 类型对象。

```java
public interface ModelLoader<T, Y> {

    DataFetcher<Y> getResourceFetcher(T model, int width, int height);
}
```

* 第 49 行，使用之前得到的 ModelLoader 对象 new 了一个 DrawableTypeRequest 返回。 apply 方法

```java
class OptionsApplier {

        public <A, X extends GenericRequestBuilder<A, ?, ?, ?>> X apply(X builder) {
            if (options != null) {
                options.apply(builder);
            }
            return builder;
        }
    }
```

####2-2-2 DrawableTypeRequest 对象

```java
    public DrawableTypeRequest<String> load(String string) {
        return (DrawableTypeRequest<String>) fromString().load(string);
    }
```

fromString() 方法返回了一个 DrawableTypeRequest 对象，我们看下这个类里具体有什么东西。

```java
public class DrawableTypeRequest<ModelType> extends DrawableRequestBuilder<ModelType> implements DownloadOptions {
    private final ModelLoader<ModelType, InputStream> streamModelLoader;
    private final ModelLoader<ModelType, ParcelFileDescriptor> fileDescriptorModelLoader;
    private final RequestManager.OptionsApplier optionsApplier;

    private static <A, Z, R> FixedLoadProvider<A, ImageVideoWrapper, Z, R> buildProvider(Glide glide,
            ModelLoader<A, InputStream> streamModelLoader,
            ModelLoader<A, ParcelFileDescriptor> fileDescriptorModelLoader, Class<Z> resourceClass,
            Class<R> transcodedClass,
            ResourceTranscoder<Z, R> transcoder) {
        if (streamModelLoader == null && fileDescriptorModelLoader == null) {
            return null;
        }

        if (transcoder == null) {
            transcoder = glide.buildTranscoder(resourceClass, transcodedClass);
        }
        DataLoadProvider<ImageVideoWrapper, Z> dataLoadProvider = glide.buildDataProvider(ImageVideoWrapper.class,
                resourceClass);
        ImageVideoModelLoader<A> modelLoader = new ImageVideoModelLoader<A>(streamModelLoader,
                fileDescriptorModelLoader);
        return new FixedLoadProvider<A, ImageVideoWrapper, Z, R>(modelLoader, transcoder, dataLoadProvider);
    }

    DrawableTypeRequest(Class<ModelType> modelClass, ModelLoader<ModelType, InputStream> streamModelLoader,
            ModelLoader<ModelType, ParcelFileDescriptor> fileDescriptorModelLoader, Context context, Glide glide,
            RequestTracker requestTracker, Lifecycle lifecycle, RequestManager.OptionsApplier optionsApplier) {
        super(context, modelClass,
                buildProvider(glide, streamModelLoader, fileDescriptorModelLoader, GifBitmapWrapper.class,
                        GlideDrawable.class, null),
                glide, requestTracker, lifecycle);
        this.streamModelLoader = streamModelLoader;
        this.fileDescriptorModelLoader = fileDescriptorModelLoader;
        this.optionsApplier = optionsApplier;
    }

    /**
     * Attempts to always load the resource as a {@link android.graphics.Bitmap}, even if it could actually be animated.
     *
     * @return A new request builder for loading a {@link android.graphics.Bitmap}
     */
    public BitmapTypeRequest<ModelType> asBitmap() {
        return optionsApplier.apply(new BitmapTypeRequest<ModelType>(this, streamModelLoader,
                fileDescriptorModelLoader, optionsApplier));
    }

    /**
     * Attempts to always load the resource as a {@link com.bumptech.glide.load.resource.gif.GifDrawable}.
     * <p>
     *     If the underlying data is not a GIF, this will fail. As a result, this should only be used if the model
     *     represents an animated GIF and the caller wants to interact with the GIfDrawable directly. Normally using
     *     just an {@link DrawableTypeRequest} is sufficient because it will determine whether or
     *     not the given data represents an animated GIF and return the appropriate animated or not animated
     *     {@link android.graphics.drawable.Drawable} automatically.
     * </p>
     *
     * @return A new request builder for loading a {@link com.bumptech.glide.load.resource.gif.GifDrawable}.
     */
    public GifTypeRequest<ModelType> asGif() {
        return optionsApplier.apply(new GifTypeRequest<ModelType>(this, streamModelLoader, optionsApplier));
    }

    ...
}
```

可以看到 DrawableTypeRequest 类提供了两个常用的方法 `asBitmapas()`、`asGif()` 方法，用来加载静态和动态图。不显示指定（就是`Glide.with().load()`方法后不显示拼接）就还是使用默认的 DrawableTypeRequest。


上面说了`fromString()`方法会返回了一个 DrawableTypeRequest 对象，这里没有 load() 方法，那应该向其父类或实现接口里找。接口里咩有，所以 load() 方法是在父类 DrawableRequestBuilder 中。

####2-2-2 DrawableTypeRequest 继承 ——> DrawableRequestBuilder 父类. load() 方法

```java
public class DrawableRequestBuilder<ModelType>
        extends GenericRequestBuilder<ModelType, ImageVideoWrapper, GifBitmapWrapper, GlideDrawable>
        implements BitmapOptions, DrawableOptions {

    DrawableRequestBuilder(Context context, Class<ModelType> modelClass,
            LoadProvider<ModelType, ImageVideoWrapper, GifBitmapWrapper, GlideDrawable> loadProvider, Glide glide,
            RequestTracker requestTracker, Lifecycle lifecycle) {
        super(context, modelClass, loadProvider, GlideDrawable.class, glide, requestTracker, lifecycle);
        // Default to animating.
        crossFade();
    }

    public DrawableRequestBuilder<ModelType> thumbnail(
            DrawableRequestBuilder<?> thumbnailRequest) {
        super.thumbnail(thumbnailRequest);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> thumbnail(
            GenericRequestBuilder<?, ?, ?, GlideDrawable> thumbnailRequest) {
        super.thumbnail(thumbnailRequest);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> thumbnail(float sizeMultiplier) {
        super.thumbnail(sizeMultiplier);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> sizeMultiplier(float sizeMultiplier) {
        super.sizeMultiplier(sizeMultiplier);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> decoder(ResourceDecoder<ImageVideoWrapper, GifBitmapWrapper> decoder) {
        super.decoder(decoder);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> cacheDecoder(ResourceDecoder<File, GifBitmapWrapper> cacheDecoder) {
        super.cacheDecoder(cacheDecoder);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> encoder(ResourceEncoder<GifBitmapWrapper> encoder) {
        super.encoder(encoder);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> priority(Priority priority) {
        super.priority(priority);
        return this;
    }

    public DrawableRequestBuilder<ModelType> transform(BitmapTransformation... transformations) {
        return bitmapTransform(transformations);
    }

    public DrawableRequestBuilder<ModelType> centerCrop() {
        return transform(glide.getDrawableCenterCrop());
    }

    public DrawableRequestBuilder<ModelType> fitCenter() {
        return transform(glide.getDrawableFitCenter());
    }

    public DrawableRequestBuilder<ModelType> bitmapTransform(Transformation<Bitmap>... bitmapTransformations) {
        GifBitmapWrapperTransformation[] transformations =
                new GifBitmapWrapperTransformation[bitmapTransformations.length];
        for (int i = 0; i < bitmapTransformations.length; i++) {
            transformations[i] = new GifBitmapWrapperTransformation(glide.getBitmapPool(), bitmapTransformations[i]);
        }
        return transform(transformations);
    }

    @Override
    public DrawableRequestBuilder<ModelType> transform(Transformation<GifBitmapWrapper>... transformation) {
        super.transform(transformation);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> transcoder(
            ResourceTranscoder<GifBitmapWrapper, GlideDrawable> transcoder) {
        super.transcoder(transcoder);
        return this;
    }

    public final DrawableRequestBuilder<ModelType> crossFade() {
        super.animate(new DrawableCrossFadeFactory<GlideDrawable>());
        return this;
    }

    public DrawableRequestBuilder<ModelType> crossFade(int duration) {
        super.animate(new DrawableCrossFadeFactory<GlideDrawable>(duration));
        return this;
    }

    public DrawableRequestBuilder<ModelType> crossFade(int animationId, int duration) {
        super.animate(new DrawableCrossFadeFactory<GlideDrawable>(context, animationId,
                duration));
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> dontAnimate() {
        super.dontAnimate();
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> animate(ViewPropertyAnimation.Animator animator) {
        super.animate(animator);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> animate(int animationId) {
        super.animate(animationId);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> placeholder(int resourceId) {
        super.placeholder(resourceId);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> placeholder(Drawable drawable) {
        super.placeholder(drawable);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> fallback(Drawable drawable) {
        super.fallback(drawable);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> fallback(int resourceId) {
        super.fallback(resourceId);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> error(int resourceId) {
        super.error(resourceId);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> error(Drawable drawable) {
        super.error(drawable);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> listener(
            RequestListener<? super ModelType, GlideDrawable> requestListener) {
        super.listener(requestListener);
        return this;
    }
    @Override
    public DrawableRequestBuilder<ModelType> diskCacheStrategy(DiskCacheStrategy strategy) {
        super.diskCacheStrategy(strategy);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> skipMemoryCache(boolean skip) {
        super.skipMemoryCache(skip);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> override(int width, int height) {
        super.override(width, height);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> sourceEncoder(Encoder<ImageVideoWrapper> sourceEncoder) {
        super.sourceEncoder(sourceEncoder);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> dontTransform() {
        super.dontTransform();
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> signature(Key signature) {
        super.signature(signature);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> load(ModelType model) {
        super.load(model);
        return this;
    }

    @Override
    public DrawableRequestBuilder<ModelType> clone() {
        return (DrawableRequestBuilder<ModelType>) super.clone();
    }

    @Override
    public Target<GlideDrawable> into(ImageView view) {
        return super.into(view);
    }

    @Override
    void applyFitCenter() {
        fitCenter();
    }

    @Override
    void applyCenterCrop() {
        centerCrop();
    }
}
```
可以看到 DrawableRequestBuilder 提供了一系列的操作方法，而这就是 Glide 大部分的 API 操作符了。比如：占位符 `placeholder()`、`error()` 方法，内存磁盘策略 `skipMemoryCache()` 、`diskCacheStrategy()` 方法。而调用这些方法就是给最终父类 GenericRequestBuilder 里的成员变量赋值，记录下用户需要做的一系列操作留给后面用。

<br>
**我们回过来看下**

```java
Glide.with(this).load(url).into(imageView);
```
**with() 方法绑定生命周期返回一个 RequsetManager 对象，再调用 load() 方法，得到根据具体入参生成一个 DrawableTypeRequest 对象，提供了若干 Glide 操作符。这里我们不叠加其他操作符分析了，最后调用 into() 方法。**

下面我们就来分析下这个 into() 方法。

<br>
###2-3 into() 方法
####2-3-1

```java
public class DrawableRequestBuilder<ModelType>
        extends GenericRequestBuilder<ModelType, ImageVideoWrapper, GifBitmapWrapper, GlideDrawable>
        implements BitmapOptions, DrawableOptions {
        ...
    @Override
    public Target<GlideDrawable> into(ImageView view) {
        return super.into(view);
    }
}
```

DrawableRequestBuilder 里 into(View v) 方法也只是调用父类的方法。我们看下父类 GenericRequestBuilder 里的 into(View v) 方法。

```java
public class GenericRequestBuilder<ModelType, DataType, ResourceType, TranscodeType> implements Cloneable {

    ...
    
    public Target<TranscodeType> into(ImageView view) {
        Util.assertMainThread();
        if (view == null) {
            throw new IllegalArgumentException("You must pass in a non null View");
        }

        if (!isTransformationSet && view.getScaleType() != null) {
            switch (view.getScaleType()) {
                case CENTER_CROP:
                    applyCenterCrop();
                    break;
                case FIT_CENTER:
                case FIT_START:
                case FIT_END:
                    applyFitCenter();
                    break;
                //$CASES-OMITTED$
                default:
                    // Do nothing.
            }
        }

        return into(glide.buildImageViewTarget(view, transcodeClass));
    }
}    
```
这里的 transform 的逻辑先不看，后面单独讲。看最后一行代码，将原来的 `view` 入参通过 `glide.buildImageViewTarget()` 方法得到一个 Target。

```java
public class Glide {

  ...

	<R> Target<R> buildImageViewTarget(ImageView imageView, Class<R> transcodedClass) {
        return imageViewTargetFactory.buildTarget(imageView, transcodedClass);
   }
}

public class ImageViewTargetFactory {

    @SuppressWarnings("unchecked")
    public <Z> Target<Z> buildTarget(ImageView view, Class<Z> clazz) {
        if (GlideDrawable.class.isAssignableFrom(clazz)) {
            return (Target<Z>) new GlideDrawableImageViewTarget(view);
        } else if (Bitmap.class.equals(clazz)) {
            return (Target<Z>) new BitmapImageViewTarget(view);
        } else if (Drawable.class.isAssignableFrom(clazz)) {
            return (Target<Z>) new DrawableImageViewTarget(view);
        } else {
            throw new IllegalArgumentException("Unhandled class: " + clazz
                    + ", try .as*(Class).transcode(ResourceTranscoder)");
        }
    }
}
```
可以看到根据传经来的 clazz 类型，返回不同的 xxxViewTarget。实际常用的就是前两种，默认 GlideDrawableImageViewTarget ，如果使用了 `asBitmap()` 就会匹配到 `BitmapImageViewRarget`。

那么这个 clazz 是怎么传进来的呢？简单看下面流程(以默认的 `GlideDrawableImageViewTarget` 为例)。
我们之前分析了 load() 方法会返回一个 `DrawableTypeRequest` 对象，而构建这个对象的时候就指定了类型是 `GlideDrawable.class`。

```java
// DrawableTypeRequest -> DrawableRequestBuilder -> GenericRequestBuilder 赋值
// this.transcodeClass = transcodeClass; //  GlideDrawable.class

// DrawableTypeRequest 父类 -> DrawableRequestBuilder
public class DrawableRequestBuilder<ModelType> ...{

	...

    DrawableRequestBuilder(Context context, Class<ModelType> modelClass,
            LoadProvider<ModelType, ImageVideoWrapper, GifBitmapWrapper, GlideDrawable> loadProvider, Glide glide,
            RequestTracker requestTracker, Lifecycle lifecycle) {
        super(context, modelClass, loadProvider, GlideDrawable.class, glide, requestTracker, lifecycle);
        // Default to animating.
        crossFade();
    }
}

// DrawableRequestBuilder 父类 -> GenericTranscodeRequest
public class GenericTranscodeRequest<ModelType, DataType, ResourceType> ... {

	...
	
	GenericRequestBuilder(Context context, Class<ModelType> modelClass,
	            LoadProvider<ModelType, DataType, ResourceType, TranscodeType> loadProvider,
	            Class<TranscodeType> transcodeClass, Glide glide, RequestTracker requestTracker, Lifecycle lifecycle) {
	        this.context = context;
	        this.modelClass = modelClass;
	        this.transcodeClass = transcodeClass;
	        this.glide = glide;
	        this.requestTracker = requestTracker;
	        this.lifecycle = lifecycle;
	        this.loadProvider = loadProvider != null
	                ? new ChildLoadProvider<ModelType, DataType, ResourceType, TranscodeType>(loadProvider) : null;
	
	        if (context == null) {
	            throw new NullPointerException("Context can't be null");
	        }
	        if (modelClass != null && loadProvider == null) {
	            throw new NullPointerException("LoadProvider must not be null");
	        }
	    }
}
```
<br>
我们再回来看通过`glide.buildImageViewTarget`得到一个`GlideDrawableImageViewTarget`后，调用 into() 方法。

```java
  return into(glide.buildImageViewTarget(view, transcodeClass));
```
```java
public class GenericRequestBuilder<ModelType, DataType, ResourceType, TranscodeType> implements Cloneable {
	...

    public <Y extends Target<TranscodeType>> Y into(Y target) {
        Util.assertMainThread();
        if (target == null) {
            throw new IllegalArgumentException("You must pass in a non null Target");
        }
        if (!isModelSet) {
            throw new IllegalArgumentException("You must first set a model (try #load())");
        }

        Request previous = target.getRequest();

        if (previous != null) {
            previous.clear();
            requestTracker.removeRequest(previous);
            previous.recycle();
        }

        Request request = buildRequest(target);
        target.setRequest(request);
        lifecycle.addListener(target);
        requestTracker.runRequest(request);

        return target;
    }
}
```
只看重点 21 行，构建了个`request`，在 24 行执行了它。其实还是调用的`request.begin()`

```java
public class RequestTracker {
	...

    /**
     * Starts tracking the given request.
     */
    public void runRequest(Request request) {
        requests.add(request);
        if (!isPaused) {
            request.begin();
        } else {
            pendingRequests.add(request);
        }
    }
}
```
`request`当然是一个接口了，所以关键就是看`Request request = buildRequest(target);`如何构建的。Request 在 Glide 里是非常关键一个组件，用来发出加载图片请求。

```java
public class GenericRequestBuilder<ModelType, DataType, ResourceType, TranscodeType> implements Cloneable {
	private Request buildRequest(Target<TranscodeType> target) {
	    if (priority == null) {
	        priority = Priority.NORMAL;
	    }
	    return buildRequestRecursive(target, null);
	}
	
	private Request buildRequestRecursive(Target<TranscodeType> target, ThumbnailRequestCoordinator parentCoordinator) {
	    if (thumbnailRequestBuilder != null) {
	        
	        ... 缩略图递归处理
	        
	    } else if (thumbSizeMultiplier != null) {
	        // Base case: thumbnail multiplier generates a thumbnail request, but cannot recurse.
	        
	        ... 缩略图处理
	        
	    } else {
	        // Base case: no thumbnail.
	        return obtainRequest(target, sizeMultiplier, priority, parentCoordinator);
	    }
	}
	
	private Request obtainRequest(Target<TranscodeType> target, float sizeMultiplier, Priority priority, RequestCoordinator requestCoordinator) {
	    return GenericRequest.obtain(
	            loadProvider,
	            model,
	            signature,
	            context,
	            priority,
	            target,
	            sizeMultiplier,
	            placeholderDrawable,
	            placeholderId,
	            errorPlaceholder,
	            errorId,
	            fallbackDrawable,
	            fallbackResource,
	            requestListener,
	            requestCoordinator,
	            glide.getEngine(),
	            transformation,
	            transcodeClass,
	            isCacheable,
	            animationFactory,
	            overrideWidth,
	            overrideHeight,
	            diskCacheStrategy);
	}
}
```
可以看到，`buildRequest()` 方法的内部其实又调用了 `buildRequestRecursive()`方法，而 `buildRequestRecursive()` 方法中的代码比较长，但是其中 90% 的代码都是在处理缩略图的。如果我们只追主线流程的话，那么只需要看第 20 行代码就可以了。

最后调用 `obtainReques()` 方法，里面依赖一堆的参数来构建一个 Request。**所以我们可以推断之前一系列操作符传入的参数，变成 GenericRequestBuilder 的成员变量，最后都用来组装最终的 Request 对象。**

而这个`GenericRequest.obtain()`方法里面就是 `new GenericRequest<A, T, Z, R>()`然后再赋值。
所以我们知道了`Request request = buildRequest(target);`得到的就是一个 GenericRequest。所以

```java
/**
     * Starts tracking the given request.
     */
    public void runRequest(Request request) {
        requests.add(request);
        if (!isPaused) {
            request.begin();
        } else {
            pendingRequests.add(request);
        }
    }

```
begin 方法的具体实现就是在 GenericRequest 类中。

<br>
####2-3-2 GenericRequest.begin()


```java
@Override
public final class GenericRequest<A, T, Z, R> implements Request, SizeReadyCallback, ResourceCallback {
	...
	
	public void begin() {
	    startTime = LogTime.getLogTime();
	    if (model == null) {
	        onException(null);
	        return;
	    }
	    status = Status.WAITING_FOR_SIZE;
	    if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
	        onSizeReady(overrideWidth, overrideHeight);
	    } else {
	        target.getSize(this);
	    }
	    if (!isComplete() && !isFailed() && canNotifyStatusChanged()) {
	        target.onLoadStarted(getPlaceholderDrawable());
	    }
	    if (Log.isLoggable(TAG, Log.VERBOSE)) {
	        logV("finished run method in " + LogTime.getElapsedMillis(startTime));
	    }
	}
}
```
**part-1 placeholder 与 error 占位图**

第 8 行，model 等于 null，会调用 onException() 方法，最终会调用到一个setErrorPlaceholder()当中。稍等下：

**问：这个 model 是啥呢？**

答：`Glide.with(context).load(xxx).into(view)`里的 xxx 就是，也就是我们之前一路看进来的 String 类型图片 URL。
下面的 load 里的入参最终给了 GenericRequest 的 model 成员变量。

```java
public class RequestManager {
	...
	
    public DrawableTypeRequest<String> load(String string) {
        return (DrawableTypeRequest<String>) fromString().load(string);
    }
}

public class DrawableRequestBuilder ...{
	...

	 @Override
    public DrawableRequestBuilder<ModelType> load(ModelType model) {
        super.load(model);
        return this;
    }
}

public class GenericRequest ...{
	...

    public GenericRequestBuilder<ModelType, DataType, ResourceType, TranscodeType> load(ModelType model) {
        this.model = model;
        isModelSet = true;
        return this;
    }
}
```
好，继续！`setErrorPlaceholder()`方法

```java
public class GenericRequest ...{
	...
	
    private void setErrorPlaceholder(Exception e) {
        if (!canNotifyStatusChanged()) {
            return;
        }

        Drawable error = model == null ? getFallbackDrawable() : null;
        if (error == null) {
          error = getErrorDrawable();
        }
        if (error == null) {
            error = getPlaceholderDrawable();
        }
        target.onLoadFailed(e, error);
    }
}
```
这个方法中会先去获取一个 error 的占位图，如果获取不到的话会再去获取一个 placeholder 占位图，然后调用 `target.onLoadFailed()` 方法并将占位图传入。那么 `onLoadFailed()` 方法中做了什么呢？我们看一下：

默认的 GlideDrawableImageViewTarget 父类 ImageViewTarget 里的方法。

```java
public abstract class ImageViewTarget<Z> extends ViewTarget<ImageView, Z> implements GlideAnimation.ViewAdapter {
    ...

    @Override
    public void onLoadStarted(Drawable placeholder) {
        view.setImageDrawable(placeholder);
    }

    @Override
    public void onLoadFailed(Exception e, Drawable errorDrawable) {
        view.setImageDrawable(errorDrawable);
    }

}
```
很明确就是将这个 `errorDrawable` 设置到 view 里。联想下 `.error() `操作符，是不是明白了呢？ 那加载过程中的操作符`.placeholder`呢？代码 18 行 `target.onLoadStarted(getPlaceholderDrawable());`。

**part-2 onSizeReady()**

复杂！！！高能预警

```java
        if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
            onSizeReady(overrideWidth, overrideHeight);
        } else {
            target.getSize(this);
        }
```
如果使用了操作符 `.override()`指定了固定宽高，直接调用 onSizeReady() 方法了。否则通过 `target.getSize(this)`去根据 ImageView 计算出图片应该的宽高，最后还是回调 onSizeReady() 方法。

```java
@Override
public void onSizeReady(int width, int height) {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logV("Got onSizeReady in " + LogTime.getElapsedMillis(startTime));
    }
    if (status != Status.WAITING_FOR_SIZE) {
        return;
    }
    status = Status.RUNNING;
    width = Math.round(sizeMultiplier * width);
    height = Math.round(sizeMultiplier * height);
    ModelLoader<A, T> modelLoader = loadProvider.getModelLoader();
    final DataFetcher<T> dataFetcher = modelLoader.getResourceFetcher(model, width, height);
    if (dataFetcher == null) {
        onException(new Exception("Failed to load model: \'" + model + "\'"));
        return;
    }
    ResourceTranscoder<Z, R> transcoder = loadProvider.getTranscoder();
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logV("finished setup for calling load in " + LogTime.getElapsedMillis(startTime));
    }
    loadedFromMemoryCache = true;
    loadStatus = engine.load(signature, width, height, dataFetcher, loadProvider, transformation, transcoder,
            priority, isMemoryCacheable, diskCacheStrategy, this);
    loadedFromMemoryCache = resource != null;
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logV("finished onSizeReady in " + LogTime.getElapsedMillis(startTime));
    }
}
```
先来看一下，在第12行调用了 `loadProvider.getModelLoader()` 方法，那么我们第一个要搞清楚的就是，这个 `loadProvider` 是什么？要搞清楚这点，需要先回到第二步的 load() 方法当中。还记得 Glide.with(xx).load() 方法是返回一个DrawableTypeRequest 对象吗？刚才我们只是分析了 DrawableTypeRequest 当中的 `asBitmap()` 和 `asGif()` 方法，并没有仔细看它的构造函数，现在我们重新来看一下 DrawableTypeRequest 类的构造函数：

```java
public class DrawableTypeRequest<ModelType> extends DrawableRequestBuilder<ModelType> implements DownloadOptions {

    private final ModelLoader<ModelType, InputStream> streamModelLoader;
    private final ModelLoader<ModelType, ParcelFileDescriptor> fileDescriptorModelLoader;
    private final RequestManager.OptionsApplier optionsApplier;

    private static <A, Z, R> FixedLoadProvider<A, ImageVideoWrapper, Z, R> buildProvider(Glide glide,
            ModelLoader<A, InputStream> streamModelLoader,
            ModelLoader<A, ParcelFileDescriptor> fileDescriptorModelLoader, Class<Z> resourceClass,
            Class<R> transcodedClass,
            ResourceTranscoder<Z, R> transcoder) {
        if (streamModelLoader == null && fileDescriptorModelLoader == null) {
            return null;
        }
        if (transcoder == null) {
            transcoder = glide.buildTranscoder(resourceClass, transcodedClass);
        }
        DataLoadProvider<ImageVideoWrapper, Z> dataLoadProvider = glide.buildDataProvider(ImageVideoWrapper.class,
                resourceClass);
        ImageVideoModelLoader<A> modelLoader = new ImageVideoModelLoader<A>(streamModelLoader,
                fileDescriptorModelLoader);
        return new FixedLoadProvider<A, ImageVideoWrapper, Z, R>(modelLoader, transcoder, dataLoadProvider);
    }

    DrawableTypeRequest(Class<ModelType> modelClass, ModelLoader<ModelType, InputStream> streamModelLoader,
            ModelLoader<ModelType, ParcelFileDescriptor> fileDescriptorModelLoader, Context context, Glide glide,
            RequestTracker requestTracker, Lifecycle lifecycle, RequestManager.OptionsApplier optionsApplier) {
        super(context, modelClass,
                buildProvider(glide, streamModelLoader, fileDescriptorModelLoader, GifBitmapWrapper.class,
                        GlideDrawable.class, null),
                glide, requestTracker, lifecycle);
        this.streamModelLoader = streamModelLoader;
        this.fileDescriptorModelLoader = fileDescriptorModelLoader;
        this.optionsApplier = optionsApplier;
    }

    ...
}
```

在 16 行，调用 `glide.buildTranscoder` 构建一个 ResourceTranscoder 用来对图片进行转码。而 ResourceTranscoder 本身是一个接口，所以这里实际对象是 ImageVideoGifDrawableLoadProvider。

在 20 行，new 了一个 ImageVideoModelLoader 的实例，并把之前 loadGeneric() 方法中构建的两个ModelLoader 封装到了 ImageVideoModelLoader 当中。

在 22 行，new 出一个 FixedLoadProvider，并把刚才构建的出来的 GifBitmapWrapperDrawableTranscoder、ImageVideoModelLoader、ImageVideoGifDrawableLoadProvider 都封装进去，这个也就是 `onSizeReady()`方法中的 `ModelLoader<A, T> modelLoader = loadProvider.getModelLoader();`。


再回到 `onSizeReady` 方法，

在 12、18 行，通过 `loadProvider` 分别调用 getModelLoader() 方法和 getTranscoder() 方法，那么得到的对象也就是刚才我们分析的 ImageVideoModelLoader 和 GifBitmapWrapperDrawableTranscoder 。

在 13 行，`final DataFetcher<T> dataFetcher = modelLoader.getResourceFetcher(model, width, height);` 这里这个 modelLoader 就是刚获取到的 ImageVieoModelLoader 了。

```java
public class ImageVideoModelLoader<A> implements ModelLoader<A, ImageVideoWrapper> {
    private static final String TAG = "IVML";

    private final ModelLoader<A, InputStream> streamLoader;
    private final ModelLoader<A, ParcelFileDescriptor> fileDescriptorLoader;

    public ImageVideoModelLoader(ModelLoader<A, InputStream> streamLoader,
            ModelLoader<A, ParcelFileDescriptor> fileDescriptorLoader) {
        if (streamLoader == null && fileDescriptorLoader == null) {
            throw new NullPointerException("At least one of streamLoader and fileDescriptorLoader must be non null");
        }
        this.streamLoader = streamLoader;
        this.fileDescriptorLoader = fileDescriptorLoader;
    }

    @Override
    public DataFetcher<ImageVideoWrapper> getResourceFetcher(A model, int width, int height) {
        DataFetcher<InputStream> streamFetcher = null;
        if (streamLoader != null) {
            streamFetcher = streamLoader.getResourceFetcher(model, width, height);
        }
        DataFetcher<ParcelFileDescriptor> fileDescriptorFetcher = null;
        if (fileDescriptorLoader != null) {
            fileDescriptorFetcher = fileDescriptorLoader.getResourceFetcher(model, width, height);
        }

        if (streamFetcher != null || fileDescriptorFetcher != null) {
            return new ImageVideoFetcher(streamFetcher, fileDescriptorFetcher);
        } else {
            return null;
        }
    }

    static class ImageVideoFetcher implements DataFetcher<ImageVideoWrapper> {
        private final DataFetcher<InputStream> streamFetcher;
        private final DataFetcher<ParcelFileDescriptor> fileDescriptorFetcher;

        public ImageVideoFetcher(DataFetcher<InputStream> streamFetcher,
                DataFetcher<ParcelFileDescriptor> fileDescriptorFetcher) {
            this.streamFetcher = streamFetcher;
            this.fileDescriptorFetcher = fileDescriptorFetcher;
        }

        ...
    }
}
```

在 20 行，通过 streamLoader.getResourceFetcher() 获取一个 DataFetcher，而这个 streamLoader 就是 RequestManager 类中的 loadGeneric() 方法中构建的 StreamStringLoader 对象 （参见章节 2-2-1）,调用 `streamLoader.getResourceFetcher()` 方法得到一个 HttpUrlFetcher 对象。

在 28 行， new ImageVideoFetcher 对象将两个 DataFetcher 对象传进去了。

<br>
**回到 OnSizeReady() 方法**

在 13 行获取到的 dataFetcher 对象就是这个 ImageVideoFetcher 对象。

最终 23 行 ` loadStatus = engine.load(signature, width, height, dataFetcher, loadProvider, transformation, transcoder,
                priority, isMemoryCacheable, diskCacheStrategy, this);`
                
将这一系列得到的对象作为 engin.load() 方法的参数传入。下面我们看下 这个 laod 方法。

                
```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {

    ...    

    public <T, Z, R> LoadStatus load(Key signature, int width, int height, DataFetcher<T> fetcher,
            DataLoadProvider<T, Z> loadProvider, Transformation<Z> transformation, ResourceTranscoder<Z, R> transcoder,
            Priority priority, boolean isMemoryCacheable, DiskCacheStrategy diskCacheStrategy, ResourceCallback cb) {
        Util.assertMainThread();
        long startTime = LogTime.getLogTime();

        final String id = fetcher.getId();
        EngineKey key = keyFactory.buildKey(id, signature, width, height, loadProvider.getCacheDecoder(),
                loadProvider.getSourceDecoder(), transformation, loadProvider.getEncoder(),
                transcoder, loadProvider.getSourceEncoder());

        EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
        if (cached != null) {
            cb.onResourceReady(cached);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from cache", startTime, key);
            }
            return null;
        }

        EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
        if (active != null) {
            cb.onResourceReady(active);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from active resources", startTime, key);
            }
            return null;
        }

        EngineJob current = jobs.get(key);
        if (current != null) {
            current.addCallback(cb);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Added to existing load", startTime, key);
            }
            return new LoadStatus(cb, current);
        }

        EngineJob engineJob = engineJobFactory.build(key, isMemoryCacheable);
        DecodeJob<T, Z, R> decodeJob = new DecodeJob<T, Z, R>(key, width, height, fetcher, loadProvider, transformation,
                transcoder, diskCacheProvider, diskCacheStrategy, priority);
        EngineRunnable runnable = new EngineRunnable(engineJob, decodeJob, priority);
        jobs.put(key, engineJob);
        engineJob.addCallback(cb);
        engineJob.start(runnable);

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Started new load", startTime, key);
        }
        return new LoadStatus(cb, engineJob);
    }

    ...
}
```               

load 方法里大篇幅都是处理缓存，下一章再将缓存。这里从 45 行开始看起。

在 45 行，构建了一个 `engineJob`，开启线程为后面的异步操作做准备。

在 48 行，创建了 runnable 任务。

在 51 行，线程启动了这个异步任务。

**EngineRunnable**

```java
class EngineRunnable implements Runnable, Prioritized {
    ...

    @Override
    public void run() {
        if (isCancelled) {
            return;
        }

        Exception exception = null;
        Resource<?> resource = null;
        try {
            resource = decode();
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Exception decoding", e);
            }
            exception = e;
        }

        if (isCancelled) {
            if (resource != null) {
                resource.recycle();
            }
            return;
        }

        if (resource == null) {
            onLoadFailed(exception);
        } else {
            onLoadComplete(resource);
        }
    }
    
    ...
    
    private Resource<?> decode() throws Exception {
        if (isDecodingFromCache()) {
            return decodeFromCache();
        } else {
            return decodeFromSource();
        }
    }

    private Resource<?> decodeFromCache() throws Exception {
        Resource<?> result = null;
        try {
            result = decodeJob.decodeResultFromCache();
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Exception decoding result from cache: " + e);
            }
        }

        if (result == null) {
            result = decodeJob.decodeSourceFromCache();
        }
        return result;
    }

    private Resource<?> decodeFromSource() throws Exception {
        return decodeJob.decodeFromSource();
    }
}
```
在 13 行，看到这个 EngineRunnable 里最主要做的事情就是这个 `resource = decode();`返回一个 Resouce 对象。

在 37 行，decode() 方法，判断是否从缓存里 decode，然后调用不同的方法。

在 45、62 行，无论是否是从缓存里decode，其实都是调用相应的 `decodeJob` 的方法。decodeJob 任务繁重，所以开启异步线程去decode xxx。

**DecodeJob**

**1）decodeFromSource 方法**

```java
class DecodeJob<A, T, Z> {

    ...

    public Resource<Z> decodeFromSource() throws Exception {
        Resource<T> decoded = decodeSource();
        return transformEncodeAndTranscode(decoded);
    }

    private Resource<T> decodeSource() throws Exception {
        Resource<T> decoded = null;
        try {
            long startTime = LogTime.getLogTime();
            final A data = fetcher.loadData(priority);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Fetched data", startTime);
            }
            if (isCancelled) {
                return null;
            }
            decoded = decodeFromSourceData(data);
        } finally {
            fetcher.cleanup();
        }
        return decoded;
    }

    ...
}
```
在 5 行，decodeFromSource 方法有两个操作，第一步调用 decodeSouce 方法获取到 Resource 对象，第二步调用`transformEncodeAndTranscode `处理第一步的对象后返回。

在 14 行，通过 `fetcher.loadData `方法获取到数据，这个 fetcher 就是我们刚才说的 onSizeReady 方法中得到的 ImageVideoFetcher 对象。

**ImageVideoFetcher.loadData()**

```java
@Override
public ImageVideoWrapper loadData(Priority priority) throws Exception {
    InputStream is = null;
    if (streamFetcher != null) {
        try {
            is = streamFetcher.loadData(priority);
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Exception fetching input stream, trying ParcelFileDescriptor", e);
            }
            if (fileDescriptorFetcher == null) {
                throw e;
            }
        }
    }
    ParcelFileDescriptor fileDescriptor = null;
    if (fileDescriptorFetcher != null) {
        try {
            fileDescriptor = fileDescriptorFetcher.loadData(priority);
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Exception fetching ParcelFileDescriptor", e);
            }
            if (is == null) {
                throw e;
            }
        }
    }
    return new ImageVideoWrapper(is, fileDescriptor);
}
```

在 6 行，ImageViewFetcher.load 方法又调用了 `streamFetcher.loadData`。这个 streamFetcher 是什么呢？就是构建 ImageVideoFetcher 对象时传入的 HttpUrlFetcher 了。

**HttpUrlFetcher.load()**

```java
public class HttpUrlFetcher implements DataFetcher<InputStream> {

    ...

    @Override
    public InputStream loadData(Priority priority) throws Exception {
        return loadDataWithRedirects(glideUrl.toURL(), 0 /*redirects*/, null /*lastUrl*/, glideUrl.getHeaders());
    }

    private InputStream loadDataWithRedirects(URL url, int redirects, URL lastUrl, Map<String, String> headers)
            throws IOException {
        if (redirects >= MAXIMUM_REDIRECTS) {
            throw new IOException("Too many (> " + MAXIMUM_REDIRECTS + ") redirects!");
        } else {
            // Comparing the URLs using .equals performs additional network I/O and is generally broken.
            // See http://michaelscharf.blogspot.com/2006/11/javaneturlequals-and-hashcode-make.html.
            try {
                if (lastUrl != null && url.toURI().equals(lastUrl.toURI())) {
                    throw new IOException("In re-direct loop");
                }
            } catch (URISyntaxException e) {
                // Do nothing, this is best effort.
            }
        }
        urlConnection = connectionFactory.build(url);
        for (Map.Entry<String, String> headerEntry : headers.entrySet()) {
          urlConnection.addRequestProperty(headerEntry.getKey(), headerEntry.getValue());
        }
        urlConnection.setConnectTimeout(2500);
        urlConnection.setReadTimeout(2500);
        urlConnection.setUseCaches(false);
        urlConnection.setDoInput(true);

        // Connect explicitly to avoid errors in decoders if connection fails.
        urlConnection.connect();
        if (isCancelled) {
            return null;
        }
        final int statusCode = urlConnection.getResponseCode();
        if (statusCode / 100 == 2) {
            return getStreamForSuccessfulRequest(urlConnection);
        } else if (statusCode / 100 == 3) {
            String redirectUrlString = urlConnection.getHeaderField("Location");
            if (TextUtils.isEmpty(redirectUrlString)) {
                throw new IOException("Received empty or null redirect url");
            }
            URL redirectUrl = new URL(url, redirectUrlString);
            return loadDataWithRedirects(redirectUrl, redirects + 1, url, headers);
        } else {
            if (statusCode == -1) {
                throw new IOException("Unable to retrieve response code from HttpUrlConnection.");
            }
            throw new IOException("Request failed " + statusCode + ": " + urlConnection.getResponseMessage());
        }
    }

    private InputStream getStreamForSuccessfulRequest(HttpURLConnection urlConnection)
            throws IOException {
        if (TextUtils.isEmpty(urlConnection.getContentEncoding())) {
            int contentLength = urlConnection.getContentLength();
            stream = ContentLengthInputStream.obtain(urlConnection.getInputStream(), contentLength);
        } else {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Got non empty content encoding: " + urlConnection.getContentEncoding());
            }
            stream = urlConnection.getInputStream();
        }
        return stream;
    }

    ...
}
```
在 7 行，HttpUrlFetcher.loadData() -> loadDataWithRedirects() 

我们终于看到网络请求了，终于知道 Glide.with().load(uri).into() 是在哪儿进行网络请求的了。

`HttpUrlFetcher.loadData()` 方法返回的是 InputStream，这里不负责解析这段服务器返回数据。再回到ImageVideoFetcher.loadData() 方法里，在第 29 行，将这个 InputStream 封装到一个ImageVideoWrapper对象里。

**回到 DecodeJob.decodeFromSource 方法**

第 14 行代码，获取到了`final A data = fetcher.loadData(priority);`获取到数据，也就是这个 ImageVideoWrapper，又在第 21 行， `decodeFromSourceData(data);` 解析这个数据。

**DecodeJob.decodeFromSourceData()**

```java
private Resource<T> decodeFromSourceData(A data) throws IOException {
    final Resource<T> decoded;
    if (diskCacheStrategy.cacheSource()) {
        decoded = cacheAndDecodeSourceData(data);
    } else {
        long startTime = LogTime.getLogTime();
        decoded = loadProvider.getSourceDecoder().decode(data, width, height);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Decoded from source", startTime);
        }
    }
    return decoded;
}
```
这里在第 7 行调用了 `loadProvider.getSourceDecoder().decode()`方法来进行解码。loadProvider 就是刚才在 onSizeReady() 方法中得到的FixedLoadProvider，而 getSourceDecoder() 得到的则是一个 GifBitmapWrapperResourceDecoder 对象，也就是要调用这个对象的decode() 方法来对图片进行解码。那么我们来看下GifBitmapWrapperResourceDecoder 的代码：

**GifBitmapWrapperResourceDecoder.decode()**

喵喵喵，太多了！

```java
public class GifBitmapWrapperResourceDecoder implements ResourceDecoder<ImageVideoWrapper, GifBitmapWrapper> {

    ...

    @SuppressWarnings("resource")
    // @see ResourceDecoder.decode
    @Override
    public Resource<GifBitmapWrapper> decode(ImageVideoWrapper source, int width, int height) throws IOException {
        ByteArrayPool pool = ByteArrayPool.get();
        byte[] tempBytes = pool.getBytes();
        GifBitmapWrapper wrapper = null;
        try {
            wrapper = decode(source, width, height, tempBytes);
        } finally {
            pool.releaseBytes(tempBytes);
        }
        return wrapper != null ? new GifBitmapWrapperResource(wrapper) : null;
    }

    private GifBitmapWrapper decode(ImageVideoWrapper source, int width, int height, byte[] bytes) throws IOException {
        final GifBitmapWrapper result;
        if (source.getStream() != null) {
            result = decodeStream(source, width, height, bytes);
        } else {
            result = decodeBitmapWrapper(source, width, height);
        }
        return result;
    }

    private GifBitmapWrapper decodeStream(ImageVideoWrapper source, int width, int height, byte[] bytes)
            throws IOException {
        InputStream bis = streamFactory.build(source.getStream(), bytes);
        bis.mark(MARK_LIMIT_BYTES);
        ImageHeaderParser.ImageType type = parser.parse(bis);
        bis.reset();
        GifBitmapWrapper result = null;
        if (type == ImageHeaderParser.ImageType.GIF) {
            result = decodeGifWrapper(bis, width, height);
        }
        // Decoding the gif may fail even if the type matches.
        if (result == null) {
            // We can only reset the buffered InputStream, so to start from the beginning of the stream, we need to
            // pass in a new source containing the buffered stream rather than the original stream.
            ImageVideoWrapper forBitmapDecoder = new ImageVideoWrapper(bis, source.getFileDescriptor());
            result = decodeBitmapWrapper(forBitmapDecoder, width, height);
        }
        return result;
    }

    private GifBitmapWrapper decodeBitmapWrapper(ImageVideoWrapper toDecode, int width, int height) throws IOException {
        GifBitmapWrapper result = null;
        Resource<Bitmap> bitmapResource = bitmapDecoder.decode(toDecode, width, height);
        if (bitmapResource != null) {
            result = new GifBitmapWrapper(bitmapResource, null);
        }
        return result;
    }

    ...
}
```

首先，在 decode() 方法中，又去调用了另外一个私有的 decode() 方法的重载。然后在第 23 行调用了 decodeStream() 方法，准备从服务器返回的流当中读取数据。decodeStream() 方法中会先从流中读取 2 个字节的数据，来判断这张图是 GIF 图还是普通的静图，如果是 GIF 图就调用 decodeGifWrapper() 方法来进行解码，如果是普通的静图就用调用 decodeBitmapWrapper() 方法来进行解码。这里我们只分析普通静图的实现流程，GIF 图的实现有点过于复杂了。

**ImageVideoBitmapDecoder.decodeBitmapWrapper()**

```java
public class ImageVideoBitmapDecoder implements ResourceDecoder<ImageVideoWrapper, Bitmap> {
    private final ResourceDecoder<InputStream, Bitmap> streamDecoder;
    private final ResourceDecoder<ParcelFileDescriptor, Bitmap> fileDescriptorDecoder;

    public ImageVideoBitmapDecoder(ResourceDecoder<InputStream, Bitmap> streamDecoder,
            ResourceDecoder<ParcelFileDescriptor, Bitmap> fileDescriptorDecoder) {
        this.streamDecoder = streamDecoder;
        this.fileDescriptorDecoder = fileDescriptorDecoder;
    }

    @Override
    public Resource<Bitmap> decode(ImageVideoWrapper source, int width, int height) throws IOException {
        Resource<Bitmap> result = null;
        InputStream is = source.getStream();
        if (is != null) {
            try {
                result = streamDecoder.decode(is, width, height);
            } catch (IOException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Failed to load image from stream, trying FileDescriptor", e);
                }
            }
        }
        if (result == null) {
            ParcelFileDescriptor fileDescriptor = source.getFileDescriptor();
            if (fileDescriptor != null) {
                result = fileDescriptorDecoder.decode(fileDescriptor, width, height);
            }
        }
        return result;
    }

    ...
}
```

代码并不复杂，在第 14 行先调用了 source.getStream() 来获取到服务器返回的 InputStream，然后在第 17 行调用 streamDecoder.decode() 方法进行解码。streamDecode 是一个 StreamBitmapDecoder 对象，那么我们再来看这个类的源码，如下所示：

**StreamBitmapDecoder**

```java
public class StreamBitmapDecoder implements ResourceDecoder<InputStream, Bitmap> {

    ...

    private final Downsampler downsampler;
    private BitmapPool bitmapPool;
    private DecodeFormat decodeFormat;

    public StreamBitmapDecoder(Downsampler downsampler, BitmapPool bitmapPool, DecodeFormat decodeFormat) {
        this.downsampler = downsampler;
        this.bitmapPool = bitmapPool;
        this.decodeFormat = decodeFormat;
    }

    @Override
    public Resource<Bitmap> decode(InputStream source, int width, int height) {
        Bitmap bitmap = downsampler.decode(source, bitmapPool, width, height, decodeFormat);
        return BitmapResource.obtain(bitmap, bitmapPool);
    }

    ...
}
```
可以看到 StreamBitmapDecoder 的 decode 方法，又是通过 downsampler 去 decode 这个 inputStream。

**Downsampler**

```java
public abstract class Downsampler implements BitmapDecoder<InputStream> {

    ...

    @Override
    public Bitmap decode(InputStream is, BitmapPool pool, int outWidth, int outHeight, DecodeFormat decodeFormat) {
        final ByteArrayPool byteArrayPool = ByteArrayPool.get();
        final byte[] bytesForOptions = byteArrayPool.getBytes();
        final byte[] bytesForStream = byteArrayPool.getBytes();
        final BitmapFactory.Options options = getDefaultOptions();
        // Use to fix the mark limit to avoid allocating buffers that fit entire images.
        RecyclableBufferedInputStream bufferedStream = new RecyclableBufferedInputStream(
                is, bytesForStream);
        // Use to retrieve exceptions thrown while reading.
        // TODO(#126): when the framework no longer returns partially decoded Bitmaps or provides a way to determine
        // if a Bitmap is partially decoded, consider removing.
        ExceptionCatchingInputStream exceptionStream =
                ExceptionCatchingInputStream.obtain(bufferedStream);
        // Use to read data.
        // Ensures that we can always reset after reading an image header so that we can still attempt to decode the
        // full image even when the header decode fails and/or overflows our read buffer. See #283.
        MarkEnforcingInputStream invalidatingStream = new MarkEnforcingInputStream(exceptionStream);
        try {
            exceptionStream.mark(MARK_POSITION);
            int orientation = 0;
            try {
                orientation = new ImageHeaderParser(exceptionStream).getOrientation();
            } catch (IOException e) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Cannot determine the image orientation from header", e);
                }
            } finally {
                try {
                    exceptionStream.reset();
                } catch (IOException e) {
                    if (Log.isLoggable(TAG, Log.WARN)) {
                        Log.w(TAG, "Cannot reset the input stream", e);
                    }
                }
            }
            options.inTempStorage = bytesForOptions;
            final int[] inDimens = getDimensions(invalidatingStream, bufferedStream, options);
            final int inWidth = inDimens[0];
            final int inHeight = inDimens[1];
            final int degreesToRotate = TransformationUtils.getExifOrientationDegrees(orientation);
            final int sampleSize = getRoundedSampleSize(degreesToRotate, inWidth, inHeight, outWidth, outHeight);
            final Bitmap downsampled =
                    downsampleWithSize(invalidatingStream, bufferedStream, options, pool, inWidth, inHeight, sampleSize,
                            decodeFormat);
            // BitmapFactory swallows exceptions during decodes and in some cases when inBitmap is non null, may catch
            // and log a stack trace but still return a non null bitmap. To avoid displaying partially decoded bitmaps,
            // we catch exceptions reading from the stream in our ExceptionCatchingInputStream and throw them here.
            final Exception streamException = exceptionStream.getException();
            if (streamException != null) {
                throw new RuntimeException(streamException);
            }
            Bitmap rotated = null;
            if (downsampled != null) {
                rotated = TransformationUtils.rotateImageExif(downsampled, pool, orientation);
                if (!downsampled.equals(rotated) && !pool.put(downsampled)) {
                    downsampled.recycle();
                }
            }
            return rotated;
        } finally {
            byteArrayPool.releaseBytes(bytesForOptions);
            byteArrayPool.releaseBytes(bytesForStream);
            exceptionStream.release();
            releaseOptions(options);
        }
    }

    private Bitmap downsampleWithSize(MarkEnforcingInputStream is, RecyclableBufferedInputStream  bufferedStream,
            BitmapFactory.Options options, BitmapPool pool, int inWidth, int inHeight, int sampleSize,
            DecodeFormat decodeFormat) {
        // Prior to KitKat, the inBitmap size must exactly match the size of the bitmap we're decoding.
        Bitmap.Config config = getConfig(is, decodeFormat);
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = config;
        if ((options.inSampleSize == 1 || Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT) && shouldUsePool(is)) {
            int targetWidth = (int) Math.ceil(inWidth / (double) sampleSize);
            int targetHeight = (int) Math.ceil(inHeight / (double) sampleSize);
            // BitmapFactory will clear out the Bitmap before writing to it, so getDirty is safe.
            setInBitmap(options, pool.getDirty(targetWidth, targetHeight, config));
        }
        return decodeStream(is, bufferedStream, options);
    }

    /**
     * A method for getting the dimensions of an image from the given InputStream.
     *
     * @param is The InputStream representing the image.
     * @param options The options to pass to
     *          {@link BitmapFactory#decodeStream(InputStream, android.graphics.Rect,
     *              BitmapFactory.Options)}.
     * @return an array containing the dimensions of the image in the form {width, height}.
     */
    public int[] getDimensions(MarkEnforcingInputStream is, RecyclableBufferedInputStream bufferedStream,
            BitmapFactory.Options options) {
        options.inJustDecodeBounds = true;
        decodeStream(is, bufferedStream, options);
        options.inJustDecodeBounds = false;
        return new int[] { options.outWidth, options.outHeight };
    }

    private static Bitmap decodeStream(MarkEnforcingInputStream is, RecyclableBufferedInputStream bufferedStream,
            BitmapFactory.Options options) {
         if (options.inJustDecodeBounds) {
             // This is large, but jpeg headers are not size bounded so we need something large enough to minimize
             // the possibility of not being able to fit enough of the header in the buffer to get the image size so
             // that we don't fail to load images. The BufferedInputStream will create a new buffer of 2x the
             // original size each time we use up the buffer space without passing the mark so this is a maximum
             // bound on the buffer size, not a default. Most of the time we won't go past our pre-allocated 16kb.
             is.mark(MARK_POSITION);
         } else {
             // Once we've read the image header, we no longer need to allow the buffer to expand in size. To avoid
             // unnecessary allocations reading image data, we fix the mark limit so that it is no larger than our
             // current buffer size here. See issue #225.
             bufferedStream.fixMarkLimit();
         }
        final Bitmap result = BitmapFactory.decodeStream(is, null, options);
        try {
            if (options.inJustDecodeBounds) {
                is.reset();
            }
        } catch (IOException e) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "Exception loading inDecodeBounds=" + options.inJustDecodeBounds
                        + " sample=" + options.inSampleSize, e);
            }
        }

        return result;
    }

    ...
}
```
可以看到，对服务器返回的 InputStream 的读取，以及对图片的加载全都在这里了。当然这里其实处理了很多的逻辑，包括对图片的压缩，甚至还有旋转、圆角等逻辑处理，但是我们目前只需要关注主线逻辑就行了。decode() 方法执行之后，会返回一个 Bitmap 对象，那么图片在这里其实也就已经被加载出来了，剩下的工作就是如果让这个 Bitmap 显示到界面上，我们继续往下分析。

回到 StreamBitmapDecoder 里，

```java
   @Override
    public Resource<Bitmap> decode(InputStream source, int width, int height) {
        Bitmap bitmap = downsampler.decode(source, bitmapPool, width, height, decodeFormat);
        return BitmapResource.obtain(bitmap, bitmapPool);
    }
```
使用 downsampler 获得了 `bitmap`， 又使用 `BitmapResource.obtain` 包装成 `Resource<Bitmap>` 对象。

```java
public class BitmapResource implements Resource<Bitmap> {
    private final Bitmap bitmap;
    private final BitmapPool bitmapPool;

    /**
     * Returns a new {@link BitmapResource} wrapping the given {@link Bitmap} if the Bitmap is non-null or null if the
     * given Bitmap is null.
     *
     * @param bitmap A Bitmap.
     * @param bitmapPool A non-null {@link com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool}.
     */
    public static BitmapResource obtain(Bitmap bitmap, BitmapPool bitmapPool) {
        if (bitmap == null) {
            return null;
        } else {
            return new BitmapResource(bitmap, bitmapPool);
        }
    }

    public BitmapResource(Bitmap bitmap, BitmapPool bitmapPool) {
        if (bitmap == null) {
            throw new NullPointerException("Bitmap must not be null");
        }
        if (bitmapPool == null) {
            throw new NullPointerException("BitmapPool must not be null");
        }
        this.bitmap = bitmap;
        this.bitmapPool = bitmapPool;
    }

    @Override
    public Bitmap get() {
        return bitmap;
    }

    @Override
    public int getSize() {
        return Util.getBitmapByteSize(bitmap);
    }

    @Override
    public void recycle() {
        if (!bitmapPool.put(bitmap)) {
            bitmap.recycle();
        }
    }
}
```

可以看到只是简单的包装了一层，通过 `Resource<Bitmap>.get()` 方法就可以获取到之前 decode() 返回的这个 bitmap 了。

然后我们需要一层层继续向上返回，StreamBitmapDecoder 会将这个 bitmap 封装成 `Resource<Bitmap>`返回到 ImageVideoBitmapDecoder当中。而 ImageVideoBitmapDecoder 又会将值返回到GifBitmapWrapperResourceDecoder 的 decodeBitmapWrapper() 方法当中(我们之前分析的静态图资源非gif)。

```java
private GifBitmapWrapper decodeBitmapWrapper(ImageVideoWrapper toDecode, int width, int height) throws IOException {
    GifBitmapWrapper result = null;
    Resource<Bitmap> bitmapResource = bitmapDecoder.decode(toDecode, width, height);
    if (bitmapResource != null) {
        result = new GifBitmapWrapper(bitmapResource, null);
    }
    return result;
}
```

然后将值包装成 GifBitmapWrapper 返回。这个 GifBitmapWrapper 就是为了兼容我们刚分析的静态资源图也能包装 Gif 图资源。

再往上返回到，GifBitmapWrapperResourceDecoder.decodeStream() 方法，再回到私有 GifBitmapWrapperResourceDecoder.decode() 方法，最后再到公有  GifBitmapWrapperResourceDecoder.decode() 方法里包装成 `Resource<GifBitmapWrapper>` 返回。

下面第 7 行

```java
public Resource<GifBitmapWrapper> decode(ImageVideoWrapper source, int width, int height) throws IOException {
    ByteArrayPool pool = ByteArrayPool.get();
    byte[] tempBytes = pool.getBytes();

    GifBitmapWrapper wrapper = null;
    try {
        wrapper = decode(source, width, height, tempBytes);
    } finally {
        pool.releaseBytes(tempBytes);
    }
    return wrapper != null ? new GifBitmapWrapperResource(wrapper) : null;
}
```
经过这一层的封装之后，我们从网络上得到的图片就能够以 Resource 接口的形式返回，并且还能同时处理 Bitmap 图片和 Gif 图片这两种情况。


##我们先捋一下

* Engine.load 封装了 engineJob，decodeJob。前者用来起线程给
任务繁重的后者。后者获取到图片 bitmap 并封装到 Resource 里。

* DecodeJob 中，会区分是否要从缓存里取图。

* 我们选择 `decodeJob.decodeFromSource();`分析。

```java
    public Resource<Z> decodeFromSource() throws Exception {
        Resource<T> decoded = decodeSource();
        return transformEncodeAndTranscode(decoded);
    }
```

* 第一步 `decodeSource()` 方法里会通过 ImageVideoFetcher 发起网络请求获取到图片的 inputstream。然后通过 GifBitmapWrapperResourceDecoder 进行图片的解析和包装（兼容静态 Bitmap 和 Gif 图片格式）。

* 第二步 transformEncodeAndTranscode（），将第一步返回的 `Resource<T>` 转化为 `Resource<Z>` 并返回。这是为啥？下面我们就来进 transformEncodeAndTranscode 方法看下。

```java
private Resource<Z> transformEncodeAndTranscode(Resource<T> decoded) {
    long startTime = LogTime.getLogTime();
    Resource<T> transformed = transform(decoded);
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logWithTimeAndKey("Transformed resource from source", startTime);
    }
    writeTransformedToCache(transformed);
    startTime = LogTime.getLogTime();
    Resource<Z> result = transcode(transformed);
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logWithTimeAndKey("Transcoded transformed from source", startTime);
    }
    return result;
}

private Resource<Z> transcode(Resource<T> transformed) {
    if (transformed == null) {
        return null;
    }
    return transcoder.transcode(transformed);
}
```

首先，这个方法开头的几行transform还有cache，这都是我们后面才会学习的东西，现在不用管它们就可以了。需要注意的是第9行，这里调用了一个transcode()方法，就把`Resource<T>`对象转换成`Resource<Z>`对象了。

这个 transcoder 又是啥？！！
那么这里我来提醒一下大家吧，在第二步load()方法返回的那个DrawableTypeRequest对象，它的构建函数中去构建了一个FixedLoadProvider对象，然后我们将三个参数传入到了FixedLoadProvider当中，其中就有一个GifBitmapWrapperDrawableTranscoder对象。后来在onSizeReady()方法中获取到了这个参数，并传递到了Engine当中，然后又由Engine传递到了DecodeJob当中。因此，这里的transcoder其实就是这个GifBitmapWrapperDrawableTranscoder对象。那么我们来看一下它的源码:

**GifBitmapWrapperDrawableTranscoder**

```java
public class GifBitmapWrapperDrawableTranscoder implements ResourceTranscoder<GifBitmapWrapper, GlideDrawable> {
    private final ResourceTranscoder<Bitmap, GlideBitmapDrawable> bitmapDrawableResourceTranscoder;

    public GifBitmapWrapperDrawableTranscoder(
            ResourceTranscoder<Bitmap, GlideBitmapDrawable> bitmapDrawableResourceTranscoder) {
        this.bitmapDrawableResourceTranscoder = bitmapDrawableResourceTranscoder;
    }

    @Override
    public Resource<GlideDrawable> transcode(Resource<GifBitmapWrapper> toTranscode) {
        GifBitmapWrapper gifBitmap = toTranscode.get();
        Resource<Bitmap> bitmapResource = gifBitmap.getBitmapResource();
        final Resource<? extends GlideDrawable> result;
        if (bitmapResource != null) {
            result = bitmapDrawableResourceTranscoder.transcode(bitmapResource);
        } else {
            result = gifBitmap.getGifResource();
        }
        return (Resource<GlideDrawable>) result;
    }

    ...
}
```
GifBitmapWrapperDrawableTranscoder 的核心作用就是用来转码的。因为 GifBitmapWrapper 是无法直接显示到 ImageView 上面的，只有 Bitmap 或者 Drawable 才能显示到 ImageView 上。因此，这里的 transcode() 方法先从 `Resource<GifBitmapWrapper>` 中取出 GifBitmapWrapper 对象，然后再从 GifBitmapWrapper 中取出Resource<Bitmap>对象。

接下来做了一个判断，如果这个对象为空，那么说明此时加载的是 Gif 图，直接调用 getGifResource() 方法将图片取出即可，因为 Glide 用于加载 Gif 图片是使用的 GifDrawable 这个类，它本身就是一个 Drawable 对象了。而如果 `Resource<Bitmap>` 不为空，那么就需要再做一次转码，将 Bitmap 转换成 Drawable 对象才行，因为要保证静图和动图的类型一致性，不然逻辑上是不好处理的。

所以在 15 行，调用了 bitmapDrawableResourceTranscoder.transcode 方法。
这个方法里就做了件事，将静态图 bitmap 包装到了 GlideBitmapDrawable 里，再将其封装成一个Resource<GlideBitmapDrawable> 对象返回。

现在再返回到 GifBitmapWrapperDrawableTranscoder 的 transcode() 方法中，你会发现它们的类型就一致了。因为不管是静图的`Resource<GlideBitmapDrawable>` 对象，还是动图的`Resource<GifDrawable>`对象，它们都是属于父类`Resource<GlideDrawable>`对象的。因此 transcode() 方法也是直接返回了`Resource<GlideDrawable>`，而这个`Resource<GlideDrawable>`其实也就是转换过后的`Resource<Z>`了。（`Resource<T> -> Resource<GifBitmapWrapper>、Resource<Z> -> Resource<GlideDrawable>`）

终于 DecodeJob 中，在 decodeFromSource() 方法得到了 `Resource<Z>` 对象，当然也就是 `Resource<GlideDrawable>` 对象。


**回到 EngineRunnable**

```java
@Override
public void run() {
    if (isCancelled) {
        return;
    }
    Exception exception = null;
    Resource<?> resource = null;
    try {
        resource = decode();
    } catch (Exception e) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Exception decoding", e);
        }
        exception = e;
    }
    if (isCancelled) {
        if (resource != null) {
            resource.recycle();
        }
        return;
    }
    if (resource == null) {
        onLoadFailed(exception);
    } else {
        onLoadComplete(resource);
    }
}
```
我们分析了一圈就是这个第 9 行，得到一个 resource，就是 Resource<GlideDrawable> 对象了。后面就是把它显示到 ImageView 上的问题了。

在 25 行，onLoadComplete 方法表示图片加载完了。

```java
private void onLoadComplete(Resource resource) {
    manager.onResourceReady(resource);
}
```
这个manager就是EngineJob对象，因此这里实际上调用的是EngineJob的onResourceReady()方法，代码如下所示：

```java
class EngineJob implements EngineRunnable.EngineRunnableManager {

    private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper(), new MainThreadCallback());

    private final List<ResourceCallback> cbs = new ArrayList<ResourceCallback>();

    ...

    public void addCallback(ResourceCallback cb) {
        Util.assertMainThread();
        if (hasResource) {
            cb.onResourceReady(engineResource);
        } else if (hasException) {
            cb.onException(exception);
        } else {
            cbs.add(cb);
        }
    }

    @Override
    public void onResourceReady(final Resource<?> resource) {
        this.resource = resource;
        MAIN_THREAD_HANDLER.obtainMessage(MSG_COMPLETE, this).sendToTarget();
    }

    private void handleResultOnMainThread() {
        if (isCancelled) {
            resource.recycle();
            return;
        } else if (cbs.isEmpty()) {
            throw new IllegalStateException("Received a resource without any callbacks to notify");
        }
        engineResource = engineResourceFactory.build(resource, isCacheable);
        hasResource = true;
        engineResource.acquire();
        listener.onEngineJobComplete(key, engineResource);
        for (ResourceCallback cb : cbs) {
            if (!isInIgnoredCallbacks(cb)) {
                engineResource.acquire();
                cb.onResourceReady(engineResource);
            }
        }
        engineResource.release();
    }

    @Override
    public void onException(final Exception e) {
        this.exception = e;
        MAIN_THREAD_HANDLER.obtainMessage(MSG_EXCEPTION, this).sendToTarget();
    }

    private void handleExceptionOnMainThread() {
        if (isCancelled) {
            return;
        } else if (cbs.isEmpty()) {
            throw new IllegalStateException("Received an exception without any callbacks to notify");
        }
        hasException = true;
        listener.onEngineJobComplete(key, null);
        for (ResourceCallback cb : cbs) {
            if (!isInIgnoredCallbacks(cb)) {
                cb.onException(exception);
            }
        }
    }

    private static class MainThreadCallback implements Handler.Callback {

        @Override
        public boolean handleMessage(Message message) {
            if (MSG_COMPLETE == message.what || MSG_EXCEPTION == message.what) {
                EngineJob job = (EngineJob) message.obj;
                if (MSG_COMPLETE == message.what) {
                    job.handleResultOnMainThread();
                } else {
                    job.handleExceptionOnMainThread();
                }
                return true;
            }
            return false;
        }
    }

    ...
}
```

可以看到，这里在onResourceReady()方法使用Handler发出了一条MSG_COMPLETE消息，那么在MainThreadCallback的handleMessage()方法中就会收到这条消息。从这里开始，所有的逻辑又回到主线程当中进行了，因为很快就需要更新UI了。

然后在第72行调用了handleResultOnMainThread()方法，这个方法中
通过 listener 通知 onEngineJobComplete （36行），又通过一个循环调用了所有ResourceCallback的onResourceReady()方法。那么这个ResourceCallback是什么呢？答案在addCallback()方法当中，它会向cbs集合中去添加ResourceCallback。那么这个addCallback()方法又是哪里调用的呢？其实调用的地方我们早就已经看过了，只不过之前没有注意，现在重新来看一下Engine的load()方法，如下所示：

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {

    ...    

    public <T, Z, R> LoadStatus load(Key signature, int width, int height, DataFetcher<T> fetcher,
            DataLoadProvider<T, Z> loadProvider, Transformation<Z> transformation, ResourceTranscoder<Z, R> transcoder, Priority priority, 
            boolean isMemoryCacheable, DiskCacheStrategy diskCacheStrategy, ResourceCallback cb) {

        ...

        EngineJob engineJob = engineJobFactory.build(key, isMemoryCacheable);
        DecodeJob<T, Z, R> decodeJob = new DecodeJob<T, Z, R>(key, width, height, fetcher, loadProvider, transformation,
                transcoder, diskCacheProvider, diskCacheStrategy, priority);
        EngineRunnable runnable = new EngineRunnable(engineJob, decodeJob, priority);
        jobs.put(key, engineJob);
        engineJob.addCallback(cb);
        engineJob.start(runnable);

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Started new load", startTime, key);
        }
        return new LoadStatus(cb, engineJob);
    }

    ...
}
```

这次把目光放在第18行上面，看到了吗？就是在这里调用的EngineJob的addCallback()方法来注册的一个ResourceCallback。那么接下来的问题就是，Engine.load()方法的ResourceCallback参数又是谁传过来的呢？这就需要回到GenericRequest的onSizeReady()方法当中了，我们看到ResourceCallback是load()方法的最后一个参数，那么在onSizeReady()方法中调用load()方法时传入的最后一个参数是什么？代码如下所示：

**GenericRequest**

```java
public final class GenericRequest<A, T, Z, R> implements Request, SizeReadyCallback,
        ResourceCallback {

    ...

    @Override
    public void onSizeReady(int width, int height) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("Got onSizeReady in " + LogTime.getElapsedMillis(startTime));
        }
        if (status != Status.WAITING_FOR_SIZE) {
            return;
        }
        status = Status.RUNNING;
        width = Math.round(sizeMultiplier * width);
        height = Math.round(sizeMultiplier * height);
        ModelLoader<A, T> modelLoader = loadProvider.getModelLoader();
        final DataFetcher<T> dataFetcher = modelLoader.getResourceFetcher(model, width, height);
        if (dataFetcher == null) {
            onException(new Exception("Failed to load model: \'" + model + "\'"));
            return;
        }
        ResourceTranscoder<Z, R> transcoder = loadProvider.getTranscoder();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished setup for calling load in " + LogTime.getElapsedMillis(startTime));
        }
        loadedFromMemoryCache = true;
        loadStatus = engine.load(signature, width, height, dataFetcher, loadProvider, transformation, 
                transcoder, priority, isMemoryCacheable, diskCacheStrategy, this);
        loadedFromMemoryCache = resource != null;
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished onSizeReady in " + LogTime.getElapsedMillis(startTime));
        }
    }

    ...
}
```
在 29 行， engine.load 方法最后一个参数 this。所以继续看 GenericRequest 中的实现类。

```java
public void onResourceReady(Resource<?> resource) {
    if (resource == null) {
        onException(new Exception("Expected to receive a Resource<R> with an object of " + transcodeClass
                + " inside, but instead got null."));
        return;
    }
    Object received = resource.get();
    if (received == null || !transcodeClass.isAssignableFrom(received.getClass())) {
        releaseResource(resource);
        onException(new Exception("Expected to receive an object of " + transcodeClass
                + " but instead got " + (received != null ? received.getClass() : "") + "{" + received + "}"
                + " inside Resource{" + resource + "}."
                + (received != null ? "" : " "
                    + "To indicate failure return a null Resource object, "
                    + "rather than a Resource object containing null data.")
        ));
        return;
    }
    if (!canSetResource()) {
        releaseResource(resource);
        // We can't set the status to complete before asking canSetResource().
        status = Status.COMPLETE;
        return;
    }
    onResourceReady(resource, (R) received);
}

private void onResourceReady(Resource<?> resource, R result) {
    // We must call isFirstReadyResource before setting status.
    boolean isFirstResource = isFirstReadyResource();
    status = Status.COMPLETE;
    this.resource = resource;
    if (requestListener == null || !requestListener.onResourceReady(result, model, target, loadedFromMemoryCache,
            isFirstResource)) {
        GlideAnimation<R> animation = animationFactory.build(loadedFromMemoryCache, isFirstResource);
        target.onResourceReady(result, animation);
    }
    notifyLoadSuccess();
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logV("Resource ready in " + LogTime.getElapsedMillis(startTime) + " size: "
                + (resource.getSize() * TO_MEGABYTE) + " fromCache: " + loadedFromMemoryCache);
    }
}
```

这里有两个onResourceReady()方法，首先在第一个onResourceReady()方法当中，调用resource.get()方法获取到了封装的图片对象，也就是GlideBitmapDrawable对象，或者是GifDrawable对象。然后将这个值传入到了第二个onResourceReady()方法当中，并在第36行调用了target.onResourceReady()方法。

那么这个target又是什么呢？这个又需要向上翻很久了，在第三步into()方法的一开始，我们就分析了在into()方法的最后一行，调用了glide.buildImageViewTarget()方法来构建出一个Target，而这个Target就是一个GlideDrawableImageViewTarget对象。

```java
public class GlideDrawableImageViewTarget extends ImageViewTarget<GlideDrawable> {
    private static final float SQUARE_RATIO_MARGIN = 0.05f;
    private int maxLoopCount;
    private GlideDrawable resource;

    public GlideDrawableImageViewTarget(ImageView view) {
        this(view, GlideDrawable.LOOP_FOREVER);
    }

    public GlideDrawableImageViewTarget(ImageView view, int maxLoopCount) {
        super(view);
        this.maxLoopCount = maxLoopCount;
    }

    @Override
    public void onResourceReady(GlideDrawable resource, GlideAnimation<? super GlideDrawable> animation) {
        if (!resource.isAnimated()) {
            float viewRatio = view.getWidth() / (float) view.getHeight();
            float drawableRatio = resource.getIntrinsicWidth() / (float) resource.getIntrinsicHeight();
            if (Math.abs(viewRatio - 1f) <= SQUARE_RATIO_MARGIN
                    && Math.abs(drawableRatio - 1f) <= SQUARE_RATIO_MARGIN) {
                resource = new SquaringDrawable(resource, view.getWidth());
            }
        }
        super.onResourceReady(resource, animation);
        this.resource = resource;
        resource.setLoopCount(maxLoopCount);
        resource.start();
    }

    /**
     * Sets the drawable on the view using
     * {@link android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
     *
     * @param resource The {@link android.graphics.drawable.Drawable} to display in the view.
     */
    @Override
    protected void setResource(GlideDrawable resource) {
        view.setImageDrawable(resource);
    }

    @Override
    public void onStart() {
        if (resource != null) {
            resource.start();
        }
    }

    @Override
    public void onStop() {
        if (resource != null) {
            resource.stop();
        }
    }
}
```
在GlideDrawableImageViewTarget的onResourceReady()方法中做了一些逻辑处理，包括如果是GIF图片的话，就调用resource.start()方法开始播放图片，但是好像并没有看到哪里有将GlideDrawable显示到ImageView上的逻辑。

确实没有，不过父类里面有，这里在第 25 行调用了 super.onResourceReady() 方法，GlideDrawableImageViewTarget 的父类是 ImageViewTarget。

```java
public abstract class ImageViewTarget<Z> extends ViewTarget<ImageView, Z> implements GlideAnimation.ViewAdapter {

    ...

    @Override
    public void onResourceReady(Z resource, GlideAnimation<? super Z> glideAnimation) {
        if (glideAnimation == null || !glideAnimation.animate(resource, this)) {
            setResource(resource);
        }
    }

    protected abstract void setResource(Z resource);

}
```

可以看到 setResource 是个抽象方法，具体实现还是在子类 GlideDrawableImageViewTarget 中。调用的view.setImageDrawable() 方法，而这个 view 就是 ImageView。代码执行到这里，图片终于也就显示出来了。

### 基本流程结束

再看到这行代码，感叹背后逻辑真心多！

```java
Glide.with(this).load(url).into(imageView);
```

