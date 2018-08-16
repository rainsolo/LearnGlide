#Glide分析三：缓存机制

## 简介

Glide 缓存分两块内容，一个是内存缓存，一个是硬盘缓存。

两种缓存作用各有侧重，前者主要防止应用重复读取图片数据到内存里，后者主要防止应用重复从网络或其他地方下载和读取数据。

##1、缓存Key

说到缓存，就肯定有用于标记缓存图片的 Key。 Glide 缓存的图片时使用的 Key 时怎样的呢？图片名字？下载链接？ 都对，但不全对，因为 Glide 的缓存 Key 生成规则非常繁琐，起影响的 Key 参数有 10 个，幸运的是逻辑异常的简单。下面我们看下：

Glide 生成 Key 的地方，在 Engine 类的 load() 方法中（这部分上篇文章分析过了）。

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {

    public <T, Z, R> LoadStatus load(Key signature, int width, int height, DataFetcher<T> fetcher,
            DataLoadProvider<T, Z> loadProvider, Transformation<Z> transformation, ResourceTranscoder<Z, R> transcoder,
            Priority priority, boolean isMemoryCacheable, DiskCacheStrategy diskCacheStrategy, ResourceCallback cb) {
        Util.assertMainThread();
        long startTime = LogTime.getLogTime();

        final String id = fetcher.getId();
        EngineKey key = keyFactory.buildKey(id, signature, width, height, loadProvider.getCacheDecoder(),
                loadProvider.getSourceDecoder(), transformation, loadProvider.getEncoder(),
                transcoder, loadProvider.getSourceEncoder());

        ...
    }

    ...
}
```

第 11 行，通过 `fetcher` 获取到一个 id，这个字符串就是我们加载图片的唯一标识。如果是从网络上下载图片的话，那这个 id 就是下载图片的 url。

第 12 行，根据 10 个参数生成一个 Key。可以看到如果通过 `.override()` 方法改变了图片的 width、height，也会生成不同的缓存 Key。

而 `keyFactory.buildKey` 就是 new 一个 EngineKey 类对象。

至于 EngineKey 类，逻辑比较简单就是重写了 equeals() 和 hashCode() 方法，保证只有这么多参数都完全一致的时候，才认为是同一个对象。

##2、内存缓存

默认情况，Glide 是开启内存缓存的。什么是内存缓存？当我们用 Glide 加载一张图片时候，如果这张图在内存中有缓存，没有被回收，那么就不用从网络上下载或者硬盘上重新读取了，这样会显著提升图片的加载效率。

如果需要在特定场景下关掉，可以使用 `.skipMemoryCache()` 方法来关闭内存缓存。

 ```java
 Glide.with(this)
     .load(url)
     .skipMemoryCache(true)
     .into(imageView);
 ```


既然内存缓存可以提升加载效率，但有一个问题就是内存不能无限占用，否则会溢出导致崩溃。那如何平衡这个效率和内存关系呢？很容易想到一个算法：LRUCache （Least Recently Used 最近最少使用算法）。原理这里就展开讲了，主要就是利用的 LinkedHashMap ，将对象的强引用存入，并排好序。当存储的对象达到预设定的上限时，清理掉最少使用的对象。先存储，后计算大小。

看起来 Glide 就是使用的 LruCache 算法做的内存缓存。嗯，这句话对但不全对，因为 Glide 还结合了弱引用缓存机制。下面从源码来分析下。

`Glide.with(this).load(...).into(view)` 

上一篇讲了 load() 方法中，分析到在 Requestmanager.loadGeneric() 方法中会调用 Glide.buildStreamModelLoader() 方法来获取一个 ModelLoader 对象。当时没有再跟进到这个方法的里面再去分析，那么我们现在来看下它的源码：


```java
public class Glide {

    public static <T> ModelLoader<T, InputStream> buildStreamModelLoader(Class<T> modelClass, Context context) {
        return buildModelLoader(modelClass, InputStream.class, context);
    }

    public static <T, Y> ModelLoader<T, Y> buildModelLoader(Class<T> modelClass, Class<Y> resourceClass,
            Context context) {
         if (modelClass == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unable to load null model, setting placeholder only");
            }
            return null;
        }
        return Glide.get(context).getLoaderFactory().buildModelLoader(modelClass, resourceClass);
    }
    
    /**
     * Get the singleton.
     *
     * @return the singleton
     */
    public static Glide get(Context context) {
        if (glide == null) {
            synchronized (Glide.class) {
                if (glide == null) {
                    Context applicationContext = context.getApplicationContext();
                    List<GlideModule> modules = new ManifestParser(applicationContext).parse();
                    GlideBuilder builder = new GlideBuilder(applicationContext);
                    for (GlideModule module : modules) {
                        module.applyOptions(applicationContext, builder);
                    }
                    glide = builder.createGlide();
                    for (GlideModule module : modules) {
                        module.registerComponents(applicationContext, glide);
                    }
                }
            }
        }
        return glide;
    }

    ...
}
```

第 4 行，构建一个 ModelLoader 对象时，调用了第 15 行，获取 Glide 的单例。

第 23 行，这个 get 方法就是一个单例构造过程。看最主要的，第 33 行，`builder.createGlide()` 构建一个 glide 对象。

```java
public class GlideBuilder {
    ...

    Glide createGlide() {
        if (sourceService == null) {
            final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            sourceService = new FifoPriorityThreadPoolExecutor(cores);
        }
        if (diskCacheService == null) {
            diskCacheService = new FifoPriorityThreadPoolExecutor(1);
        }
        MemorySizeCalculator calculator = new MemorySizeCalculator(context);
        if (bitmapPool == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                int size = calculator.getBitmapPoolSize();
                bitmapPool = new LruBitmapPool(size);
            } else {
                bitmapPool = new BitmapPoolAdapter();
            }
        }
        if (memoryCache == null) {
            memoryCache = new LruResourceCache(calculator.getMemoryCacheSize());
        }
        if (diskCacheFactory == null) {
            diskCacheFactory = new InternalCacheDiskCacheFactory(context);
        }
        if (engine == null) {
            engine = new Engine(memoryCache, diskCacheFactory, diskCacheService, sourceService);
        }
        if (decodeFormat == null) {
            decodeFormat = DecodeFormat.DEFAULT;
        }
        return new Glide(engine, memoryCache, bitmapPool, context, decodeFormat);
    }
}
```

这里也就是构建 Glide 对象的地方了。那么观察第 22 行，你会发现这里 new 出了一个 LruResourceCache，并把它赋值到了 `memoryCache` 这个对象上面。这个就是我们内存缓存使用到的 LruCache 对象了。

第 28 行，可以看到刚才 Engine 对象的生成过程，仔细看下几个入参，就有我们使用到的`memoryCache`。

第 1 节，我们已经看过缓存 Key 是如何在 Engine 中生成的，现在我们重新来看一下 Engine 类 load() 方法的完整源码。

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

第 17 行，`loadFromCache()`方法获取缓存，如果取得到，直接 `cb.onResourceReady()` 回调。

第 26 行，如果之前的缓存没有获取到，再调用 `loadFromActiveResources()` 方法获取缓存图片，如果取得到，也通过`cb.onResourceReady()` 回调。

只有这两种缓存都取不到的时候，才往下执行，开启线程加载图片。

这就是前面我们说的 Glide 会使用两种内存缓存，所以这中间一个是用来 LruCache 算法，另一个结合了弱引用缓存。

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {

    private final MemoryCache cache;
    private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
    ...

    private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {
            return null;
        }
        EngineResource<?> cached = getEngineResourceFromCache(key);
        if (cached != null) {
            cached.acquire();
            activeResources.put(key, new ResourceWeakReference(key, cached, getReferenceQueue()));
        }
        return cached;
    }

    private EngineResource<?> getEngineResourceFromCache(Key key) {
        Resource<?> cached = cache.remove(key);
        final EngineResource result;
        if (cached == null) {
            result = null;
        } else if (cached instanceof EngineResource) {
            result = (EngineResource) cached;
        } else {
            result = new EngineResource(cached, true /*isCacheable*/);
        }
        return result;
    }

    private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {
            return null;
        }
        EngineResource<?> active = null;
        WeakReference<EngineResource<?>> activeRef = activeResources.get(key);
        if (activeRef != null) {
            active = activeRef.get();
            if (active != null) {
                active.acquire();
            } else {
                activeResources.remove(key);
            }
        }
        return active;
    }

    ...
}
```

第 22 行，调用 getEngineResourceFromCache() 方法获取缓存。而 getEngineResourceFromCache() 方法中可以看到，获得缓存图片的方式是将其从 Lru 缓存中移除，然后在第 16 行加入到 `activeResources` 中。`activeResources`就是我们说的另一种缓存策略，弱引用的 HashMap。

第 34 行，loadFromActiveResources() 方法就是从这个 `activeResources` 中获取图片的。

为什么使用`activeResources`缓存呢？**它可以保护正在使用中的图片不会被 LruCache 算法给回收掉。**

从内存缓存读取数据逻辑总结下：如果能从两种内存缓存中读取到要加载的图片，就直接进行回调，否则，开启线程执行后面的图片加载逻辑。

###2.1内存缓存何时写入
搞明白如何读取使用缓存，下一步我们要搞明白的是这些缓存是何时被写入的。上一篇我们讲过，图片开启线程加载完成后 EngineJob 会通过 Handler 发消息回调到主线程,执行 handleResultOnMainThread() 方法。

```java
class EngineJob implements EngineRunnable.EngineRunnableManager {

    private final EngineResourceFactory engineResourceFactory;
    ...

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

    static class EngineResourceFactory {
        public <R> EngineResource<R> build(Resource<R> resource, boolean isMemoryCacheable) {
            return new EngineResource<R>(resource, isMemoryCacheable);
        }
    }
    ...
}
```

第 13 行，通过 EngineResourceFactory 构建出了一个包含图片资源的EngineResource 对象，然后会在第 16 行将这个对象回调到 Engine 的onEngineJobComplete() 方法当中。

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {
    ...    

    @Override
    public void onEngineJobComplete(Key key, EngineResource<?> resource) {
        Util.assertMainThread();
        // A null resource indicates that the load failed, usually due to an exception.
        if (resource != null) {
            resource.setResourceListener(key, this);
            if (resource.isCacheable()) {
                activeResources.put(key, new ResourceWeakReference(key, resource, getReferenceQueue()));
            }
        }
        jobs.remove(key);
    }

    ...
}
```

非常明显了，在第 13 行，回调到主线程后，将 EngineResource 加入到 activeResources 缓存中去。

这里只是弱引用缓存，那 LruCache 缓存是什么时候写入的呢？这个需要介绍下弱引用缓存的机制了。观察刚才的 handleResultOnMainThread() 方法，在第 15 行和第 19 行有调用 EngineResource的acquire() 方法，在第 23 行有调用它的 release() 方法。其实，EngineResource 是用一个 acquired 变量用来记录图片被引用的次数，调用acquire() 方法会让变量加1，调用 release() 方法会让变量减1（联想下垃圾回收的引用计数法），代码如下所示：

```java
class EngineResource<Z> implements Resource<Z> {

    private int acquired;
    ...

    void acquire() {
        if (isRecycled) {
            throw new IllegalStateException("Cannot acquire a recycled resource");
        }
        if (!Looper.getMainLooper().equals(Looper.myLooper())) {
            throw new IllegalThreadStateException("Must call acquire on the main thread");
        }
        ++acquired;
    }

    void release() {
        if (acquired <= 0) {
            throw new IllegalStateException("Cannot release a recycled or not yet acquired resource");
        }
        if (!Looper.getMainLooper().equals(Looper.myLooper())) {
            throw new IllegalThreadStateException("Must call release on the main thread");
        }
        if (--acquired == 0) {
            listener.onResourceReleased(key, this);
        }
    }
}
```

第 6 行，调用 acquire() 方法，`acquired`计数变量 +1

第 16 行，调用 release() 方法，`acquired`计数变量 -1

第 24 行，当 `acquired`计数变量等于 0 时，说明图片不再被使用了，调用 onResourceReleased 回调。

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {

    private final MemoryCache cache;
    private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
    ...    

    @Override
    public void onResourceReleased(Key cacheKey, EngineResource resource) {
        Util.assertMainThread();
        activeResources.remove(cacheKey);
        if (resource.isCacheable()) {
            cache.put(cacheKey, resource);
        } else {
            resourceRecycler.recycle(resource);
        }
    }

    ...
}
```

第 12 行，从 activeResources 缓存中移除，并在第 14 行加入到 LruCache 缓存中。这样也就实现了正在使用中的图片使用弱引用来进行缓存，不在使用中的图片使用LruCache 来进行缓存的功能。

这就是 Glide 内存缓存的实现原理。

###2.2 什么时候调用 EngineResource.release 方法

我们已经知道缓存如何使用，也知道了图片加载完后如何加入到缓存里的，我们甚至知道图片是如何在两种内存缓存中过渡的。但我们还缺了一块，就是何时会调用 EngineResource.release 方法。 我们再回头看下 handleResultOnMainThread 方法：

```java
class EngineJob implements EngineRunnable.EngineRunnableManager {

    private final EngineResourceFactory engineResourceFactory;
    ...

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

    static class EngineResourceFactory {
        public <R> EngineResource<R> build(Resource<R> resource, boolean isMemoryCacheable) {
            return new EngineResource<R>(resource, isMemoryCacheable);
        }
    }
    ...
}
```

第 17～22 行，遍历回调集合。在 19 行，调用 engineResource.acquire() 方法，引用计数+1，再在第 20 行，回调通知 onResourceReady()。这个回调接口的实现在 GenericRequeset.onResourceReady 里，并且该方法里没有再调用 engineResource 的 release()方法。所以在 23 行，调用 engineResource.release() 后，引用计数可能还是大于 0。那图片还是缓存在弱引用 activeResources 缓存里，那什么时候会再调用 engineResource.release()呢？


要回到这个问题，我们需要回到上一篇我们讲过的关于 Glide 绑定生命周期的部分。
Glide.with() 方法得到一个绑定了生命周期的 RequestManager，也就是说在这个 RequestManager 里我们可以同步收到生命周期的回调。下面我们再看下详细代码：

```java
public class RequestManager implements LifecycleListener {
	...
	
    /**
     * Lifecycle callback that registers for connectivity events (if the android.permission.ACCESS_NETWORK_STATE
     * permission is present) and restarts failed or paused requests.
     */
    @Override
    public void onStart() {
        // onStart might not be called because this object may be created after the fragment/activity's onStart method.
        resumeRequests();
    }

    /**
     * Lifecycle callback that unregisters for connectivity events (if the android.permission.ACCESS_NETWORK_STATE
     * permission is present) and pauses in progress loads.
     */
    @Override
    public void onStop() {
        pauseRequests();
    }

    /**
     * Lifecycle callback that cancels all in progress requests and clears and recycles resources for all completed
     * requests.
     */
    @Override
    public void onDestroy() {
        requestTracker.clearRequests();
    }
}
```

第 29 行，当收到生命周期结束的回调通知时，会调用`requestTracker.clearRequests()`方法。我们再看下 RequestTracker 代码：

```java
public class RequestTracker {
    private final Set<Request> requests = Collections.newSetFromMap(new WeakHashMap<Request, Boolean>());
   
    private final List<Request> pendingRequests = new ArrayList<Request>();

    private boolean isPaused;
    
    ...
    
    /**
     * Cancels all requests and clears their resources.
     */
    public void clearRequests() {
        for (Request request : Util.getSnapshot(requests)) {
            request.clear();
        }
        pendingRequests.clear();
    }

}
```

第 13 行， clearRequests 方法里，遍历 request 集合调用其 clear 方法。这些 request 就是上一篇我们讲过的 GenericRequest。我们进去 GenericRequest 里看下 clear 方法。

```java
public final class GenericRequest<A, T, Z, R> implements Request, SizeReadyCallback, ResourceCallback {

    @Override
    public void clear() {
        Util.assertMainThread();
        if (status == Status.CLEARED) {
            return;
        }
        cancel();
        // Resource must be released before canNotifyStatusChanged is called.
        if (resource != null) {
            releaseResource(resource);
        }
        if (canNotifyStatusChanged()) {
            target.onLoadCleared(getPlaceholderDrawable());
        }
        // Must be after cancel().
        status = Status.CLEARED;
    }
    
    ...
    
    private void releaseResource(Resource resource) {
        engine.release(resource);
        this.resource = null;
    }
}
```

第 12 行，调用 releaseResource() 方法。

第 23 行，调用 engine.release() 方法。

追进去看下 Engine 类：

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {
        ...
        
    public void release(Resource resource) {
        Util.assertMainThread();
        if (resource instanceof EngineResource) {
            ((EngineResource) resource).release();
        } else {
            throw new IllegalArgumentException("Cannot release anything but an EngineResource");
        }
    }
    
 }   
```

第 9 行，我们终于看到  ((EngineResource) resource).release() 方法了。

我们总结下，在 RequestManager 收到生命周期 onDestroy 的回调后，会最终调用使用到的 EngineResource 的 release() 方法。

好，以上这就是要讲的内存缓存的全部内容了。


##3、硬盘缓存

我们还是先看段使用代码：

```java
Glide.with(this)
     .load(url)
     .diskCacheStrategy(DiskCacheStrategy.NONE)
     .into(imageView);
```

这段代码禁用了硬盘缓存。而这个 diskCacheStrategy() 方法基本上就是 Glide 硬盘缓存功能的一切，它可以接收四种参数：

* DiskCacheStrategy.NONE： 表示不缓存任何内容。
* DiskCacheStrategy.SOURCE： 表示只缓存原始图片。
* DiskCacheStrategy.RESULT： 表示只缓存转换过后的图片（**默认选项**）。
* DiskCacheStrategy.ALL ： 表示既缓存原始图片，也缓存转换过后的图片。

上面四种参数的解释本身并没有什么难理解的地方，但是有一个概念大家需要了解，就是当我们使用 Glide 去加载一张图片的时候，Glide 默认并不会将原始图片展示出来，而是会对图片进行压缩和转换（我们会在后面学习这方面的内容）。总之就是经过种种一系列操作之后得到的图片，就叫转换过后的图片。而 Glide 默认情况下在硬盘缓存的就是转换过后的图片，我们通过调用 diskCacheStrategy() 方法则可以改变这一默认行为。

和内存缓存类似，硬盘缓存的实现也是使用的 LruCache 算法，而且 Google 还提供了一个自己编写的工具类 DiskLruCache。

接下来我们看一下 Glide 是在哪里读取硬盘缓存的。回到上篇文章中的内容，Glide 在从内存缓冲获取不到图片后，会开启线程来加载图片。在执行 EngineRunnable 的 run() 方法时候调用一个decode()方法。这里我们需要重新来看一下这个 decode() 方法的源码：

```java
class EngineRunnable implements Runnable, Prioritized {
	...
	
	private Resource<?> decode() throws Exception {
	    if (isDecodingFromCache()) {
	        return decodeFromCache();
	    } else {
	        return decodeFromSource();
	    }
	}
}
```

可以看到，这里会分为两种情况，一种是调用 decodeFromCache() 方法从硬盘缓存当中读取图片，一种是调用 decodeFromSource() 来读取原始图片。默认情况下 Glide 会优先通过前者读取，当取不到的时候才会去读取原始图片。从下面的 decodeFromCache() 方法的源码，如下所示：

```java
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
```

第 4 行与第 11 行，先从 DecodeJob 的 decodeResultFromCache() 方法来获取缓存，如果获取不到，再调用 decodeSourceFromCache() 方法获取缓存。这两个方法的区别其实就是 DiskCacheStrategy.RESULT 和 DiskCacheStrategy.SOURCE 这两个参数的区别，就是取决于我们指定哪个硬盘缓存策略。默认 RESULT。

```java
public Resource<Z> decodeResultFromCache() throws Exception {
    if (!diskCacheStrategy.cacheResult()) {
        return null;
    }
    long startTime = LogTime.getLogTime();
    Resource<T> transformed = loadFromCache(resultKey);
    startTime = LogTime.getLogTime();
    Resource<Z> result = transcode(transformed);
    return result;
}

public Resource<Z> decodeSourceFromCache() throws Exception {
    if (!diskCacheStrategy.cacheSource()) {
        return null;
    }
    long startTime = LogTime.getLogTime();
    Resource<T> decoded = loadFromCache(resultKey.getOriginalKey());
    return transformEncodeAndTranscode(decoded);
}
```

可以看到两个方法，第一部分，都是检查此次加载的硬盘缓存策略是否符合。第二部分，都是调用 loadFromCache() 方法去获取内容，只是使用的 key 不一样。

* decodeResultFromCache() 方法  -> resultKey
* decodeSourceFromCache() 方法  -> resultKey.getOriginalKey

这个刚才我们已经解释过了，Glide 的缓存 Key 是由 10 个参数共同组成的，包括图片的 width、height 等等。但如果我们是缓存的原始图片 SOURCE ，其实并不需要这么多的参数，因为不用对图片做任何的变化。那么我们来看一下 getOriginalKey() 方法的源码：

```java
public Key getOriginalKey() {
    if (originalKey == null) {
        originalKey = new OriginalKey(id, signature);
    }
    return originalKey;
}
```
可以看到，只使用了 id 和 signature 这两个参数来构成缓存 Key。而 signature 参数绝大多数情况下都是用不到的，因此基本上可以说就是由 id（也就是图片url）来决定的 Original 缓存 Key。

搞明白了这两种缓存 Key 的区别，那么接下来我们看一下 loadFromCache() 方法的源码.

```java
private Resource<T> loadFromCache(Key key) throws IOException {
    File cacheFile = diskCacheProvider.getDiskCache().get(key);
    if (cacheFile == null) {
        return null;
    }
    Resource<T> result = null;
    try {
        result = loadProvider.getCacheDecoder().decode(cacheFile, width, height);
    } finally {
        if (result == null) {
            diskCacheProvider.getDiskCache().delete(key);
        }
    }
    return result;
}
```
这个方法的逻辑非常简单，调用 getDiskCache() 方法获取到的就是 Glide 自己编写的DiskLruCache 工具类的实例，然后通过入参 Key 就能得到硬盘缓存的文件。如果文件为空就返回 null，如果文件不为空则将它解码成 Resource 对象后返回即可。

上面我们分析的是 decodeFromCache() 方法从硬盘缓存当中读取图片，下面我们要开始分析 decodeFromSource() 方法，看 Glide 是如何读取原始图片的。

```java
public Resource<Z> decodeFromSource() throws Exception {
    Resource<T> decoded = decodeSource();
    return transformEncodeAndTranscode(decoded);
}
```

第 2 行，解析原图片。

第 3 行，对解析得到的图片进行转换和转码。

我们先看 decodeSource() 方法。

```java
private Resource<T> decodeSource() throws Exception {
    Resource<T> decoded = null;
    try {
        long startTime = LogTime.getLogTime();
        final A data = fetcher.loadData(priority);
        if (isCancelled) {
            return null;
        }
        decoded = decodeFromSourceData(data);
    } finally {
        fetcher.cleanup();
    }
    return decoded;
}

private Resource<T> decodeFromSourceData(A data) throws IOException {
    final Resource<T> decoded;
    if (diskCacheStrategy.cacheSource()) {
        decoded = cacheAndDecodeSourceData(data);
    } else {
        long startTime = LogTime.getLogTime();
        decoded = loadProvider.getSourceDecoder().decode(data, width, height);
    }
    return decoded;
}

private Resource<T> cacheAndDecodeSourceData(A data) throws IOException {
    long startTime = LogTime.getLogTime();
    SourceWriter<A> writer = new SourceWriter<A>(loadProvider.getSourceEncoder(), data);
    diskCacheProvider.getDiskCache().put(resultKey.getOriginalKey(), writer);
    startTime = LogTime.getLogTime();
    Resource<T> result = loadFromCache(resultKey.getOriginalKey());
    return result;
}
```
第 5 行，调用 fetcher.loadData() 方法读取图片数据

第 9 行, 调用 decodeFromSourceData() 方法来对图片进行解码。

第 18 行, 先判断是否允许缓存原始图片，如果允许的话又会调用cacheAndDecodeSourceData() 方法。

第 30 行，同样调用了 getDiskCache() 方法来获取 DiskLruCache 实例。接着调用它的put() 方法将图片数据写入硬盘缓存了，注意缓存Key是用的 resultKey.getOriginalKey()。

总结下在 decodeSource() 方法中，Glide 完成了原始图片的下载和写入硬盘缓存。

接下来我们分析一下 decodeSource() 方法调用之后的 transformEncodeAndTranscode() 方法。来看看将原始图片转换后，做了什么操作。代码如下所示：

```java
private Resource<Z> transformEncodeAndTranscode(Resource<T> decoded) {
    long startTime = LogTime.getLogTime();
    Resource<T> transformed = transform(decoded);
    writeTransformedToCache(transformed);
    startTime = LogTime.getLogTime();
    Resource<Z> result = transcode(transformed);
    return result;
}

private void writeTransformedToCache(Resource<T> transformed) {
    if (transformed == null || !diskCacheStrategy.cacheResult()) {
        return;
    }
    long startTime = LogTime.getLogTime();
    SourceWriter<Resource<T>> writer = new SourceWriter<Resource<T>>(loadProvider.getEncoder(), transformed);
    diskCacheProvider.getDiskCache().put(resultKey, writer);
}
```

逻辑很简单有没有？

第 3 行，转换原始图片。

第 4 行，调用 writeTransformedToCache() 方法将转换后的图片写入硬盘缓存。

第 11 行，对入参检查，然后更具硬盘缓存策略，判断是否需要将转换后的数据写入硬盘缓存。

第 16 行，写入硬盘缓存，使用的 Key 是 resultKey。

到这里 Glide 硬盘缓存的实现原理也分析完了，主要逻辑依赖 diskCacheStrategy() 方法传入的硬盘缓存策略。

## 4、高级应用

虽说 Glide 将缓存功能高度封装之后，使得用法变得非常简单，但同时也带来了一些问题。

比如项目的图片资源都是存放在七牛云上面的，而七牛云为了对图片资源进行保护，会在图片url 地址的基础之上再加上一个 token 参数。也就是说，一张图片的url地址可能会是如下格式：

> http://url.com/image.jpg?token=d9caa6e02c990b0a

而使用 Glide 加载这张图片的话，也就会使用这个 url 地址来组成缓存 Key。如果这个 token 有时效性，随时可能会变，那就会导致 Glide 的缓存策略失效。明明是同一张图片，因为每次加载 token 的不同，而没法命中缓存。（我们项目中七牛云图片链接我检查了下，暂时没有追加 token，url 每次不变）

我们再看下 Glide 缓存 Key 生成的地方：

```java
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {

    public <T, Z, R> LoadStatus load(Key signature, int width, int height, DataFetcher<T> fetcher,
            DataLoadProvider<T, Z> loadProvider, Transformation<Z> transformation, ResourceTranscoder<Z, R> transcoder,
            Priority priority, boolean isMemoryCacheable, DiskCacheStrategy diskCacheStrategy, ResourceCallback cb) {
        Util.assertMainThread();
        long startTime = LogTime.getLogTime();

        final String id = fetcher.getId();
        EngineKey key = keyFactory.buildKey(id, signature, width, height, loadProvider.getCacheDecoder(),
                loadProvider.getSourceDecoder(), transformation, loadProvider.getEncoder(),
                transcoder, loadProvider.getSourceEncoder());

        ...
    }

    ...
}
```

来看一下第 11 行，刚才已经说过了，这个 id 其实就是图片的 url 地址。那么，这里是通过调用 fetcher.getId() 方法来获取的图片 url 地址，而我们在上一篇文章中已经知道了，fetcher 就是 HttpUrlFetcher 的实例(如果我们配置使用了 OkHttp，这里就是 OkHttpStreamFetcher)。 所以我们的思路就是如果让诸如七牛云这样带 token 的图片链接返回的 id 都是一样的。

我们看一下它的 HttpUrlFetcher.getId() 方法的源码吧，如下所示：

```java
public class HttpUrlFetcher implements DataFetcher<InputStream> {

    private final GlideUrl glideUrl;
    ...

    public HttpUrlFetcher(GlideUrl glideUrl) {
        this(glideUrl, DEFAULT_CONNECTION_FACTORY);
    }

    HttpUrlFetcher(GlideUrl glideUrl, HttpUrlConnectionFactory connectionFactory) {
        this.glideUrl = glideUrl;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String getId() {
        return glideUrl.getCacheKey();
    }

    ...
}
```

第 17 行，通过 `glideUrl.getCacheKey()`获取这个 id。首先这个 GlideUrl 对象是从哪里来的呢？其实就是我们在 load() 方法中传入的图片 url 地址，然后 Glide 在内部把这个 url 地址包装成了一个 GlideUrl 对象。我们看下简化代码后的 GlideUrl 类：

```java
public class GlideUrl {

    private final URL url;
    private final String stringUrl;
    ...

    public GlideUrl(URL url) {
        this(url, Headers.DEFAULT);
    }

    public GlideUrl(String url) {
        this(url, Headers.DEFAULT);
    }

    public GlideUrl(URL url, Headers headers) {
        ...
        this.url = url;
        stringUrl = null;
    }

    public GlideUrl(String url, Headers headers) {
        ...
        this.stringUrl = url;
        this.url = null;
    }

    public String getCacheKey() {
        return stringUrl != null ? stringUrl : url.toString();
    }

    ...
}
```
GlideUrl 类的构造函数接收两种类型的参数，一种是 url 字符串，一种是 URL 对象。然后 getCacheKey() 方法中的判断逻辑非常简单，如果传入的是 url 字符串，那么就直接返回这个字符串本身，如果传入的是 URL 对象，那么就返回这个对象 toString() 后的结果。

所以我们整理下思路，我们直接自己构造个 GlideUrl 类对象，在使用 Glide.with().load() 方法时候传入这个 GlideUrl 对象。最关键的就是 getCacheKey() 方法返回我们处理字符串做构建 Glide 缓存 Key 的 id。

看代码：

```java
public class MyGlideUrl extends GlideUrl {

    private String mUrl;

    public MyGlideUrl(String url) {
        super(url);
        mUrl = url;
    }

    @Override
    public String getCacheKey() {
        return getUrlWithoutToken(mUrl);
    }

    private String getUrlWithoutToken(String originUrl) {
        int tokenKeyIndex = originUrl.contains("?token=") ? originUrl.indexOf("?token=") : originUrl.indexOf("&token=");
        if (tokenKeyIndex >= 0) {
            int nextAndIndex = originUrl.indexOf("&", tokenKeyIndex + 1);
            if (nextAndIndex >= 0) {
                return originUrl.substring(0, tokenKeyIndex + 1) + originUrl.substring(nextAndIndex + 1);
            } else {
                return originUrl.substring(0, tokenKeyIndex);
            }
        }
        return originUrl;
    }
}
```
这样 getCacheKey() 方法得到的就是一个没有 token 参数的 url 地址，从而不管 token 怎么变化，最终 Glide 的缓存 Key 都是固定不变的了。

使用方法如下：

```java
Glide.with(this)
     .load(new MyGlideUrl(url))
     .into(imageView);
```

到这里，Glide 的缓存机制就全部结束了。撒花～

