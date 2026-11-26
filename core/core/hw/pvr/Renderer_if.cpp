#include "Renderer_if.h"
#include "spg.h"
#include "rend/texconv.h"
#include "rend/transform_matrix.h"
#include "cfg/option.h"
#include "emulator.h"
#include "serialize.h"
#include "hw/holly/holly_intc.h"
#include "hw/sh4/sh4_if.h"
#include "hw/sh4/sh4_core.h"
#include "hw/sh4/sh4_sched.h"
#include "profiler/fc_profiler.h"
#include "network/ggpo.h"

#include <chrono>
#include <exception>
#include <mutex>
#include <condition_variable>
#include <deque>
#include <memory>
#ifdef LIBRETRO
#include "util/deferred_exception.h"
static DeferredException renderFailure;
#endif

#ifdef LIBRETRO
void retro_rend_present();
void retro_resize_renderer(int w, int h, float aspectRatio);
#endif

u32 FrameCount=1;

Renderer* renderer;

static cResetEvent renderEnd;
u32 fb_w_cur = 1;
static cResetEvent vramRollback;

// direct framebuffer write detection
static bool render_called = false;
u32 fb_watch_addr_start;
u32 fb_watch_addr_end;
bool fb_dirty;

static bool pend_rend;
static bool rendererEnabled = true;

static bool presented;
static u32 fbAddrHistory[2] { 1, 1 };

// Lockstep state. A rendered frame is "pending" (emu thread view of the
// renderer's frameRendered flag) until the Present that stops the SH4 like the
// single-threaded renderer does.
static bool lockstepFramePending;
// Emu thread view of the renderer's clearLastFrame flag: set on Terminate,
// cleared by the next rendered frame. Present() fails while it is set.
static bool lockstepClearLastFrame;
static std::mutex lockstepMutex;
static std::condition_variable lockstepCond;
static bool lockstepGranted;
static bool lockstepDone = true;
static bool lockstepAborted;
static int lockstepBlocked;
// Render messages queued by the emu thread / that reached the point where the
// renderer releases the emu thread (renderEnd). Counting instead of waiting on
// the auto-reset renderEnd event can't lose or coalesce a signal.
static s64 lockstepRendersQueued;
static s64 lockstepRendersDone;

static void rend_lockstep_render_queued();
static void rend_lockstep_render_done();
static void rend_lockstep_wait_renders();

// Delay frame swapping is a threaded-rendering feature (see the libretro core
// option help). With the single-threaded renderer the emulation thread is
// stopped when a frame is presented, so delaying the present until the game
// swaps its framebuffer leaves a run to end on the 50 ms vblank timeout
// instead. Games whose swap pattern doesn't match FB_R_SOF == FB_W_SOF (many
// Naomi/Atomiswave titles) then advance an unpredictable number of vblanks per
// frontend frame, which makes the frame pacing wander (~40 FPS) and the audio
// stutter. Only honour the option when the threaded renderer is active.
static bool delayFrameSwapping()
{
	// The lockstep renderer stops the SH4 when the Present is queued, so the
	// swap can't be delayed to the render thread.
	return config::ThreadedRendering && config::DelayFrameSwapping && !rend_is_lockstep();
}

class PvrMessageQueue
{
	using lock_guard = std::lock_guard<std::mutex>;

public:
	enum MessageType { NoMessage = -1, Render, RenderFramebuffer, Present, Stop };
	struct Message
	{
		Message() = default;
		Message(MessageType type, FramebufferInfo config)
			: type(type), config(config) {}

		MessageType type = NoMessage;
		FramebufferInfo config;
	};

	void enqueue(MessageType type, FramebufferInfo config = FramebufferInfo())
	{
		Message msg { type, config };
		if (config::ThreadedRendering)
		{
			// FIXME need some synchronization to avoid blinking in densha de go
			// or use !threaded rendering for emufb?
			// or read framebuffer vram on emu thread
			bool dupe;
			do {
				dupe = false;
				{
					const lock_guard lock(mutex);
					for (const auto& m : queue)
						if (m.type == type) {
							dupe = true;
							break;
						}
					if (!dupe || type == Present) {
						// Bound the queue to keep the emu-thread producer and
						// the renderer-thread consumer from drifting apart.
						// Render/RenderFramebuffer/Stop are already deduplicated
						// above, but Present is intentionally allowed to repeat
						// and can stack up indefinitely if the consumer stalls
						// (notably under libretro frontends, which drive the
						// swap from their own video callback). Unbounded growth
						// here is the producer side of progressive audio/video
						// drift in long sessions: the SH4 keeps running ahead
						// while pending Presents pile up. Drop the oldest
						// pending Present to keep latency bounded.
						// Not in lockstep mode, where the queue only holds one
						// run's work and the emu thread already decided whether
						// each Present stops the SH4.
						constexpr size_t MAX_QUEUE_DEPTH = 4;
						if (queue.size() >= MAX_QUEUE_DEPTH && !rend_is_lockstep())
						{
							for (auto it = queue.begin(); it != queue.end(); ++it)
							{
								if (it->type == Present)
								{
									queue.erase(it);
									break;
								}
							}
						}
						if (type == Render && rend_is_lockstep())
							// Lock order: queue mutex, then lockstep mutex
							rend_lockstep_render_queued();
						queue.push_back(msg);
						dupe = false;
					}
				}
				if (dupe)
				{
					if (type == Stop)
						return;
					rend_lockstep_wait(dequeueEvent);
					if (rend_is_lockstep() && rend_lockstep_aborted())
						// The emulator is being stopped and nobody serves the queue
						return;
				}
			} while (dupe);
			enqueueEvent.Set();
			if (rend_is_lockstep() && type != Stop)
			{
				// Same stop condition as the single-threaded renderer, where
				// the SH4 is stopped if Present() succeeds: a frame was rendered
				// and clearLastFrame isn't set (see OpenGLRenderer::Present).
				if (type == RenderFramebuffer)
				{
					lockstepFramePending = true;
					lockstepClearLastFrame = false;
				}
				else if (type == Present && lockstepFramePending && !lockstepClearLastFrame)
				{
					lockstepFramePending = false;
					if (!ggpo::active())
						emu.getSh4Executor()->Stop();
				}
			}
		}
		else
		{
			setDefaultRoundingMode();
#ifdef LIBRETRO
			const bool succeeded = renderFailure.run([&] {
#endif
			// drain the queue after switching to !threaded rendering
			while (!queue.empty())
				waitAndExecute();
			execute(msg);
#ifdef LIBRETRO
			});
			if (!succeeded) {
				renderEnd.Set();
				rend_allow_rollback();
				emu.getSh4Executor()->Stop();
			}
#endif
			Sh4cntx.restoreHostRoundingMode();
		}
	}

	bool waitAndExecute(int timeoutMs = -1)
	{
		return execute(dequeue(timeoutMs));
	}

	// Executes one queued message. Returns false if there was none.
	bool executeOne(int timeoutMs)
	{
		Message msg = dequeue(timeoutMs);
		if (msg.type == NoMessage)
			return false;
		execute(msg);
		return true;
	}

	size_t size() {
		const lock_guard lock(mutex);
		return queue.size();
	}

	void reset() {
		const lock_guard lock(mutex);
		queue.clear();
	}

	void cancelEnqueue()
	{
		const lock_guard lock(mutex);
		// In lockstep mode the queue holds the work of the last run, which the
		// next retro_run executes. Dropping its RenderFramebuffer/Present would
		// lose a frame on every stop/start (save states, option changes).
		if (!rend_is_lockstep())
			for (auto it = queue.begin(); it != queue.end(); )
			{
				if (it->type != Render)
					it = queue.erase(it);
				else
					++it;
			}
		dequeueEvent.Set();
	}
private:
	Message dequeue(int timeoutMs = -1)
	{
		FC_PROFILE_SCOPE;

		Message msg;
		while (true)
		{
			{
				const lock_guard lock(mutex);
				if (!queue.empty())
				{
					msg = queue.front();
					queue.pop_front();
				}
			}
			if (msg.type != NoMessage) {
				dequeueEvent.Set();
				break;
			}
			if (timeoutMs == -1)
				enqueueEvent.Wait();
			else if (!enqueueEvent.Wait(timeoutMs))
				break;
		}
		return msg;
	}

	bool execute(Message msg)
	{
		switch (msg.type)
		{
		case Render:
			render();
			return true;
		case RenderFramebuffer:
			renderFramebuffer(msg.config);
			return true;
		case Present:
			present();
			return true;
		case Stop:
		case NoMessage:
		default:
			return false;
		}
	}

	void render()
	{
		FC_PROFILE_SCOPE;

		// Releases the emu thread waiting in rend_end_render(). In lockstep mode
		// every dequeued Render is accounted for, even if it fails or is empty.
		struct RenderEnd
		{
			bool signaled = false;
			void signal() {
				signaled = true;
				renderEnd.Set();
				if (rend_is_lockstep())
					rend_lockstep_render_done();
			}
			~RenderEnd() {
				if (!signaled && rend_is_lockstep())
					rend_lockstep_render_done();
			}
		} end;
		TA_context *taContext = DequeueRender();
		if (taContext == nullptr)
			return;
		const std::unique_ptr<TA_context, decltype(&FinishRender)> finish(taContext, FinishRender);

		// tile clipping is used to calculate framebuffer size in RTT below
		setTileClipping(taContext->rend);
		int width, height;
		getScaledFramebufferSize(taContext->rend, width, height);
		taContext->rend.framebufferWidth = width;
		taContext->rend.framebufferHeight = height;
		bool renderToScreen = !taContext->rend.isRTT && !config::EmulateFramebuffer;
#ifdef LIBRETRO
		if (renderToScreen)
			retro_resize_renderer(taContext->rend.framebufferWidth, taContext->rend.framebufferHeight,
					getOutputFramebufferAspectRatio());
#endif
		{
			FC_PROFILE_SCOPE_NAMED("Renderer::Process");
			try {
				renderer->Process(taContext);
			} catch (...) {
				end.signal();
				rend_allow_rollback();
				throw;
			}
		}

		if (renderToScreen)
			// If rendering to texture or in full framebuffer emulation, continue locking until the frame is rendered
			end.signal();
		rend_allow_rollback();
		{
			FC_PROFILE_SCOPE_NAMED("Renderer::Render");
			try {
				renderer->Render();
			} catch (...) {
				if (!renderToScreen)
					end.signal();
				throw;
			}
		}

		if (!renderToScreen)
			end.signal();
		else if (delayFrameSwapping() && fb_w_cur == FB_R_SOF1)
			present();

		//clear up & free data ..
	}

	void renderFramebuffer(const FramebufferInfo& config)
	{
		FC_PROFILE_SCOPE;

#ifdef LIBRETRO
		int w, h;
		getDCFramebufferReadSize(config, w, h);
		retro_resize_renderer(w, h, getDCFramebufferAspectRatio());
#endif
		renderer->RenderFramebuffer(config);
	}

	void present()
	{
		FC_PROFILE_SCOPE;

		if (renderer->Present())
		{
			presented = true;
			if (!config::ThreadedRendering && !ggpo::active())
				emu.getSh4Executor()->Stop();
#ifdef LIBRETRO
			retro_rend_present();
#endif
		}
	}

	std::mutex mutex;
	cResetEvent enqueueEvent;
	cResetEvent dequeueEvent;
	std::deque<Message> queue;
};

static PvrMessageQueue pvrQueue;

#ifdef LIBRETRO
void rend_rethrow_deferred_exception() {
	renderFailure.rethrow();
}
#endif

bool rend_single_frame(const bool& enabled)
{
	FC_PROFILE_SCOPE;

	const int timeout = SPG_CONTROL.isPAL() ? 23 : 20;
	presented = false;
	while (enabled && !presented)
		if (!pvrQueue.waitAndExecute(timeout))
			return false;
	return true;
}

bool rend_is_lockstep()
{
#ifdef LIBRETRO
	return config::ThreadedRendering && !config::GGPOEnable;
#else
	return false;
#endif
}

void rend_lockstep_reset()
{
	const std::lock_guard<std::mutex> lock(lockstepMutex);
	lockstepGranted = false;
	lockstepDone = true;
	lockstepAborted = false;
	lockstepBlocked = 0;
}

void rend_lockstep_abort()
{
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepAborted = true;
	}
	lockstepCond.notify_all();
}

bool rend_lockstep_aborted()
{
	const std::lock_guard<std::mutex> lock(lockstepMutex);
	return lockstepAborted;
}

bool rend_lockstep_wait_grant()
{
	std::unique_lock<std::mutex> lock(lockstepMutex);
	lockstepCond.wait(lock, [] { return lockstepGranted || lockstepAborted; });
	if (lockstepAborted)
		return false;
	lockstepGranted = false;
	return true;
}

void rend_lockstep_done()
{
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepDone = true;
	}
	lockstepCond.notify_all();
}

// Blocking wait of the emu thread on something the render side provides. The
// frontend thread, which owns the GL context, serves the render queue while the
// emu thread is blocked.
void rend_lockstep_wait(cResetEvent& event)
{
	if (!rend_is_lockstep()) {
		event.Wait();
		return;
	}
	if (event.Wait(0))
		return;
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepBlocked++;
	}
	lockstepCond.notify_all();
	// Nobody serves the queue once the emulator is being stopped
	while (!event.Wait(10) && !rend_lockstep_aborted())
		;
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepBlocked--;
	}
}

static void rend_lockstep_render_queued()
{
	const std::lock_guard<std::mutex> lock(lockstepMutex);
	lockstepRendersQueued++;
}

static void rend_lockstep_render_done()
{
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepRendersDone++;
	}
	lockstepCond.notify_all();
}

// Emu thread: wait until every queued Render has released the emu thread
// (rend_end_render), as the single-threaded renderer has done it already.
static void rend_lockstep_wait_renders()
{
	std::unique_lock<std::mutex> lock(lockstepMutex);
	if (lockstepRendersDone >= lockstepRendersQueued || lockstepAborted)
		return;
	lockstepBlocked++;
	lockstepCond.notify_all();
	lockstepCond.wait(lock, [] { return lockstepRendersDone >= lockstepRendersQueued || lockstepAborted; });
	lockstepBlocked--;
}

// Frontend thread: the emu thread has been blocked for a long time with nothing
// left in the queue to serve, which should never happen. Release every wait
// rather than hang forever.
static void rend_lockstep_unblock()
{
	WARN_LOG(RENDERER, "Lockstep renderer: emulation thread stuck, releasing it");
	FinishRender(DequeueRender());
	renderEnd.Set();
	rend_allow_rollback();
	pvrQueue.cancelEnqueue();
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepRendersDone = lockstepRendersQueued;
	}
	lockstepCond.notify_all();
}

// Frontend thread state of the run in progress
static bool lockstepRunning;
static std::exception_ptr lockstepFailure;

// Executes one queued message. A render failure is rethrown once the run is
// over, so that the emu thread isn't left running (or blocked) while the
// frontend handles it.
static bool rend_lockstep_execute(int timeoutMs)
{
	try {
		return pvrQueue.executeOne(timeoutMs);
	} catch (...) {
		if (!lockstepFailure)
			lockstepFailure = std::current_exception();
		return true;
	}
}

bool rend_lockstep_begin()
{
	FC_PROFILE_SCOPE;

	// Work queued by the previous run. It is executed while the SH4 runs ahead.
	size_t backlog = pvrQueue.size();
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		if (lockstepAborted)
			return false;
		lockstepDone = false;
		lockstepGranted = true;
	}
	lockstepCond.notify_all();
	lockstepRunning = true;
	lockstepFailure = nullptr;

	while (backlog > 0 && rend_lockstep_execute(0))
		backlog--;
	return true;
}

void rend_lockstep_end()
{
	FC_PROFILE_SCOPE;

	if (!lockstepRunning)
		return;
	lockstepRunning = false;
	// Wait for the end of the run. New render messages are left in the queue for
	// the next run, unless the emu thread is blocked waiting for them.
	using clock = std::chrono::steady_clock;
	constexpr auto watchdogTimeout = std::chrono::seconds(2);
	clock::time_point idleSince {};
	std::unique_lock<std::mutex> lock(lockstepMutex);
	while (!lockstepDone && !lockstepAborted)
	{
		if (lockstepBlocked > 0)
		{
			lock.unlock();
			if (rend_lockstep_execute(1))
				idleSince = {};
			else if (idleSince == clock::time_point{})
				idleSince = clock::now();
			else if (clock::now() - idleSince > watchdogTimeout)
			{
				rend_lockstep_unblock();
				idleSince = {};
			}
			lock.lock();
		}
		else
		{
			idleSince = {};
			lockstepCond.wait(lock);
		}
	}
	lock.unlock();
	if (lockstepFailure)
	{
		std::exception_ptr failure = lockstepFailure;
		lockstepFailure = nullptr;
		std::rethrow_exception(failure);
	}
}

void rend_lockstep_run()
{
	if (rend_lockstep_begin())
		rend_lockstep_end();
}

class SwapIntervalDetector
{
public:
	SwapIntervalDetector() {
		EventManager::listen(Event::LoadState, eventHandler, this);
		reset();
	}
	~SwapIntervalDetector() {
		EventManager::unlisten(Event::LoadState, eventHandler, this);
	}

	void render()
	{
		u64 now = sh4_sched_now64();
		if (lastRender != 0)
			renderInterval = now - lastRender;
		lastRender = now;
		renders++;
	}

	void vblank()
	{
		avgRenderInterval = 0.1f * renderInterval + 0.9f * avgRenderInterval;

		// Force transition to 60 FPS if the game swap interval is 1 for 3 consecutive frames.
		// Displaying a 60 FPS game at 30 FPS makes the game run in slo-mo and breaks audio.
		if (renders != 0)
		{
			renders = 0;
			rendersFullSpeed++;
			if (rendersFullSpeed >= 3)
			{
				// force 60/50 FPS now
				lastInterval = 1;
				currentInterval = 1;
				return;
			}
		}
		else {
			rendersFullSpeed = 0;
		}

		const float vblankPerRender = avgRenderInterval / (SPG_CONTROL.isPAL() ? 20_sh4ms : 16667_sh4us);
		int interval = std::round(vblankPerRender);
		float frac = std::abs(vblankPerRender - interval);

		if ((interval == 2 && frac <= .05f)
				|| (interval == 1 && frac <= .2f))
		{
			if (lastInterval == (int)interval)
			{
				if (++stable >= 10)
					currentInterval = std::min(lastInterval, 2);
				unstable = 0;
			}
			else
			{
				stable = 0;
				lastInterval = interval;
				unstable++;
			}
		}
		else {
			stable = 0;
			unstable++;
		}
		if (unstable >= 30 && vblankPerRender < 2.f)
			// Force swap interval to 1 if the frame rate is off over 30 frames
			// Helps with games that render slightly above 30 FPS (ECCO 33 FPS, Armada ~40 FPS)
			currentInterval = 1;
	}

	int swapInterval() const {
		return currentInterval;
	}

	void reset()
	{
		lastInterval = 1;
		stable = 0;
		unstable = 0;
		currentInterval = 1;

		lastRender = 0;
		renderInterval = 0;
		avgRenderInterval = 0.f;
		renders = 0;
		rendersFullSpeed = 0;
	}

private:
	static void eventHandler(Event event, void *arg) {
		SwapIntervalDetector *self = (SwapIntervalDetector *)arg;
		self->lastRender = 0;
		self->lastInterval = 1;
	}

	int lastInterval;
	int stable;
	int unstable;
	int currentInterval;

	u64 lastRender;
	u64 renderInterval;
	float avgRenderInterval;
	int renders;
	int rendersFullSpeed;
};
static SwapIntervalDetector swapIntervalDetector;

// Renderer::onEvent sets clearLastFrame on Terminate, which makes Present()
// fail until a frame is rendered. Mirror it for the lockstep stop condition.
static struct LockstepTerminateListener
{
	LockstepTerminateListener() {
		EventManager::listen(Event::Terminate, onTerminate, this);
	}
	~LockstepTerminateListener() {
		EventManager::unlisten(Event::Terminate, onTerminate, this);
	}
	static void onTerminate(Event, void *) {
		lockstepClearLastFrame = true;
	}
} lockstepTerminateListener;


Renderer* rend_GLES2();
Renderer* rend_GL4();
Renderer* rend_norend();
Renderer* rend_Vulkan();
Renderer* rend_OITVulkan();
Renderer* rend_DirectX9();
Renderer* rend_DirectX11();
Renderer* rend_OITDirectX11();

static void rend_create_renderer()
{
#ifdef NO_REND
	renderer	 = rend_norend();
#else
	switch (config::RendererType)
	{
	default:
#ifdef USE_OPENGL
	case RenderType::OpenGL:
		renderer = rend_GLES2();
		break;
#if !defined(GLES2) && !defined(__APPLE__)
	case RenderType::OpenGL_OIT:
		renderer = rend_GL4();
		break;
#endif
#endif
#ifdef USE_VULKAN
	case RenderType::Vulkan:
		renderer = rend_Vulkan();
		break;
	case RenderType::Vulkan_OIT:
		renderer = rend_OITVulkan();
		break;
#endif
#ifdef USE_DX9
	case RenderType::DirectX9:
		renderer = rend_DirectX9();
		break;
#endif
#ifdef USE_DX11
	case RenderType::DirectX11:
		renderer = rend_DirectX11();
		break;
	case RenderType::DirectX11_OIT:
		renderer = rend_OITDirectX11();
		break;
#endif
	}
#endif
}

bool rend_init_renderer()
{
	rendererEnabled = true;
	if (renderer == nullptr)
	{
		rend_create_renderer();
		// A new renderer has neither a rendered frame nor clearLastFrame set
		lockstepFramePending = false;
		lockstepClearLastFrame = false;
	}
	bool success = renderer != nullptr && renderer->Init();
	if (!success) {
		delete renderer;
		renderer = rend_norend();
		renderer->Init();
	}
	return success;
}

void rend_term_renderer()
{
	// Drain and stop the queue first so that any in-flight Render/Present
	// messages cannot be dispatched against a renderer that is about to be
	// destroyed. This is called from many libretro entry points (context
	// reset/destroy, deinit, content load, renderer switch) where the emu
	// thread may still be holding queued work; without cancelling first
	// we can race the consumer thread into a null renderer dereference and
	// also leave the producer side wedged, which manifests as audio/video
	// drift after a renderer switch.
	rend_cancel_emu_wait();

	if (renderer != nullptr)
	{
		renderer->Term();
		delete renderer;
		renderer = nullptr;
	}
}

void rend_reset()
{
#ifdef LIBRETRO
	renderFailure.reset();
#endif
	FinishRender(DequeueRender());
	render_called = false;
	pend_rend = false;
	lockstepFramePending = false;
	FrameCount = 1;
	fb_w_cur = 1;
	pvrQueue.reset();
	{
		const std::lock_guard<std::mutex> lock(lockstepMutex);
		lockstepRendersDone = lockstepRendersQueued;
	}
	rendererEnabled = true;
	fbAddrHistory[0] = 1;
	fbAddrHistory[1] = 1;
	swapIntervalDetector.reset();
}

void rend_start_render()
{
	render_called = true;
	pend_rend = false;

	TA_context *ctx = nullptr;
	u32 addresses[MAX_PASSES];
	int count = getTAContextAddresses(addresses);
	if (count > 0)
	{
		ctx = tactx_Pop(addresses[0]);
		if (ctx != nullptr)
		{
			TA_context *linkedCtx = ctx;
			for (int i = 1; i < count; i++)
			{
				linkedCtx->nextContext = tactx_Pop(addresses[i]);
				if (linkedCtx->nextContext != nullptr)
					linkedCtx = linkedCtx->nextContext;
				else
					INFO_LOG(PVR, "rend_start_render: Context%d @ %x not found", i, addresses[i]);
			}
		}
		else
			INFO_LOG(PVR, "rend_start_render: Context0 @ %x not found", addresses[0]);
	}
	else
		INFO_LOG(PVR, "rend_start_render: No context not found");

	scheduleRenderDone(ctx);

	if (ctx == nullptr)
		return;

	FillBGP(ctx);

	ctx->rend.isRTT = (FB_W_SOF1 & 0x1000000) != 0;
	ctx->rend.fb_W_SOF1 = FB_W_SOF1;
	ctx->rend.fb_W_CTRL.full = FB_W_CTRL.full;

	ctx->rend.globClip.x = (TA_GLOB_TILE_CLIP.tile_x_num + 1) * 32;
	ctx->rend.globClip.y = (TA_GLOB_TILE_CLIP.tile_y_num + 1) * 32;
	ctx->rend.scaler_ctl = SCALER_CTL;
	ctx->rend.fbClip.origin.x = FB_X_CLIP.min;
	ctx->rend.fbClip.origin.y = FB_Y_CLIP.min;
	ctx->rend.fbClip.size.x = FB_X_CLIP.max - FB_X_CLIP.min + 1;
	ctx->rend.fbClip.size.y = FB_Y_CLIP.max - FB_Y_CLIP.min + 1;
	ctx->rend.fb_W_LINESTRIDE = FB_W_LINESTRIDE.stride;

	ctx->rend.fog_clamp_min = FOG_CLAMP_MIN;
	ctx->rend.fog_clamp_max = FOG_CLAMP_MAX;

	if (!ctx->rend.isRTT)
	{
		if (FB_W_SOF1 != fbAddrHistory[0] && FB_W_SOF1 != fbAddrHistory[1])
		{
			ctx->rend.clearFramebuffer = true;
			fbAddrHistory[0] = fbAddrHistory[1];
			fbAddrHistory[1] = FB_W_SOF1;
		}
		else {
			ctx->rend.clearFramebuffer = false;
		}
		ggpo::endOfFrame();
		swapIntervalDetector.render();
		if (!config::EmulateFramebuffer)
			ctx->rend.swapInterval = swapIntervalDetector.swapInterval();
		else
			ctx->rend.swapInterval = 1;
	}

	if (QueueRender(ctx))
	{
		palette_update();
		pend_rend = true;
		const bool presentFrame = !delayFrameSwapping() && !ctx->rend.isRTT && !config::EmulateFramebuffer;
		if (!ctx->rend.isRTT && !config::EmulateFramebuffer)
		{
			lockstepFramePending = true;
			lockstepClearLastFrame = false;
		}
		pvrQueue.enqueue(PvrMessageQueue::Render);
		if (presentFrame)
			pvrQueue.enqueue(PvrMessageQueue::Present);
	}
}

int rend_end_render(int tag, int cycles, int jitter, void *arg)
{
	if (settings.platform.isNaomi2())
	{
		asic_RaiseInterruptBothCLX(holly_RENDER_DONE);
		asic_RaiseInterruptBothCLX(holly_RENDER_DONE_isp);
		asic_RaiseInterruptBothCLX(holly_RENDER_DONE_vd);
	}
	else
	{
		asic_RaiseInterrupt(holly_RENDER_DONE);
		asic_RaiseInterrupt(holly_RENDER_DONE_isp);
		asic_RaiseInterrupt(holly_RENDER_DONE_vd);
	}
	if (pend_rend && config::ThreadedRendering)
	{
		if (rend_is_lockstep())
			rend_lockstep_wait_renders();
		else
			renderEnd.Wait();
	}

	return 0;
}

void rend_vblank()
{
	if (config::EmulateFramebuffer
			|| (!render_called && fb_dirty && FB_R_CTRL.fb_enable))
	{
		if (rend_is_enabled())
		{
			FramebufferInfo fbInfo;
			fbInfo.update();
			pvrQueue.enqueue(PvrMessageQueue::RenderFramebuffer, fbInfo);
			pvrQueue.enqueue(PvrMessageQueue::Present);
			if (!config::EmulateFramebuffer)
				DEBUG_LOG(PVR, "Direct framebuffer write detected");
		}
		fb_dirty = false;
	}
	render_called = false;
	check_framebuffer_write();
	emu.vblank();
	swapIntervalDetector.vblank();
}

void check_framebuffer_write()
{
	u32 fb_size = (FB_R_SIZE.fb_y_size + 1) * (FB_R_SIZE.fb_x_size + FB_R_SIZE.fb_modulus) * 4;
	fb_watch_addr_start = (SPG_CONTROL.interlace ? FB_R_SOF2 : FB_R_SOF1) & VRAM_MASK;
	fb_watch_addr_end = fb_watch_addr_start + fb_size;
}

void rend_cancel_emu_wait()
{
	if (rend_is_lockstep())
	{
		// The emu thread is woken by rend_lockstep_abort(). The queued messages
		// are kept for the next run, and renderEnd isn't signaled: a stale
		// signal would let a later rend_end_render() skip its wait.
		FinishRender(NULL);
		rend_allow_rollback();
		pvrQueue.cancelEnqueue();
	}
	else if (config::ThreadedRendering)
	{
		FinishRender(NULL);
		renderEnd.Set();
		rend_allow_rollback();
		pvrQueue.cancelEnqueue();
		// Needed for android where this function may be called
		// from a thread different from the UI one
		pvrQueue.enqueue(PvrMessageQueue::Stop);
	}
}

void rend_set_fb_write_addr(u32 fb_w_sof1)
{
	if (fb_w_sof1 & 0x1000000)
		// render to texture
		return;
	fb_w_cur = fb_w_sof1;
}

void rend_swap_frame(u32 fb_r_sof)
{
	if (!config::EmulateFramebuffer && fb_r_sof == fb_w_cur && rend_is_enabled())
		pvrQueue.enqueue(PvrMessageQueue::Present);
}

void rend_disable_rollback()
{
	vramRollback.Reset();
}

void rend_allow_rollback()
{
	vramRollback.Set();
}

void rend_start_rollback()
{
	if (config::ThreadedRendering)
		vramRollback.Wait();
}

void rend_enable_renderer(bool enabled) {
	rendererEnabled = enabled;
}

bool rend_is_enabled() {
	return rendererEnabled;
}

void rend_serialize(Serializer& ser)
{
	ser << fb_w_cur;
	ser << render_called;
	ser << fb_dirty;
	ser << fb_watch_addr_start;
	ser << fb_watch_addr_end;
}
void rend_deserialize(Deserializer& deser)
{
	deser >> fb_w_cur;
	if (deser.version() >= Deserializer::V20)
	{
		deser >> render_called;
		deser >> fb_dirty;
		deser >> fb_watch_addr_start;
		deser >> fb_watch_addr_end;
	}
	pend_rend = false;
	fbAddrHistory[0] = 1;
	fbAddrHistory[1] = 1;
}
