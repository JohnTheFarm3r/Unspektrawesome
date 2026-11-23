#include "thread_pool.h"

#include <algorithm>
#include <cstdio>
#include <string>

#if defined(__linux__) || defined(__ANDROID__)
#include <sched.h>
#include <unistd.h>
#define URAW_AFFINITY 1
#endif

namespace uraw::detail {

namespace {

#if defined(URAW_AFFINITY)
// Reads one unsigned number from a sysfs file, 0 when missing.
unsigned long readNumber(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "r");
    if (!f) return 0;
    unsigned long v = 0;
    if (std::fscanf(f, "%lu", &v) != 1) v = 0;
    std::fclose(f);
    return v;
}

void pinCurrentThread(const std::vector<int>& cpus) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : cpus) CPU_SET(c, &set);
    sched_setaffinity(0, sizeof(set), &set);  // best effort; ignored when not permitted
}
#endif

}  // namespace

std::vector<int> performanceCpus(int threads) {
    std::vector<int> out;
#if defined(URAW_AFFINITY)
    const long n = sysconf(_SC_NPROCESSORS_CONF);
    std::vector<unsigned long> score(static_cast<size_t>(n > 0 ? n : 0));
    for (long c = 0; c < n; ++c) {
        const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(c);
        unsigned long v = readNumber(base + "/cpu_capacity");
        if (!v) v = readNumber(base + "/cpufreq/cpuinfo_max_freq");
        score[static_cast<size_t>(c)] = v;
    }
    if (score.empty()) return out;
    const auto [lo, hi] = std::minmax_element(score.begin(), score.end());
    if (*lo == 0 || *lo == *hi) return out;  // unknown or all cores alike

    // Clusters = cores of equal score, fastest first. The slowest cluster is never used.
    std::vector<std::pair<unsigned long, std::vector<int>>> clusters;
    for (long c = 0; c < n; ++c) {
        const unsigned long s = score[static_cast<size_t>(c)];
        if (s == *lo) continue;
        auto it = std::find_if(clusters.begin(), clusters.end(), [s](const auto& g) { return g.first == s; });
        if (it == clusters.end()) clusters.push_back({s, {static_cast<int>(c)}});
        else it->second.push_back(static_cast<int>(c));
    }
    std::sort(clusters.begin(), clusters.end(), [](const auto& a, const auto& b) { return a.first > b.first; });

    // Prefer one cluster that fits every thread: shared clock and cache, no migrations.
    if (threads > 0) {
        for (const auto& g : clusters)
            if (static_cast<int>(g.second.size()) >= threads) return g.second;
    }
    // Otherwise take clusters from the fastest down until there are enough cores.
    for (const auto& g : clusters) {
        out.insert(out.end(), g.second.begin(), g.second.end());
        if (threads > 0 && static_cast<int>(out.size()) >= threads) break;
    }
    std::sort(out.begin(), out.end());
#else
    (void)threads;
#endif
    return out;
}

int ThreadPool::defaultThreads() {
    const unsigned n = std::thread::hardware_concurrency();
    return n ? static_cast<int>(std::min(n, 64u)) : 4;
}

ThreadPool::ThreadPool(int threads, bool performanceCores) {
    if (performanceCores) cpus_ = performanceCpus(threads);
    if (threads <= 0) threads = cpus_.empty() ? defaultThreads() : static_cast<int>(cpus_.size());
    // The calling thread is one of the `threads`.
    for (int i = 1; i < threads; ++i) workers_.emplace_back([this] { workerLoop(cpus_); });
}

AffinityScope::AffinityScope(const std::vector<int>& cpus) {
#if defined(URAW_AFFINITY)
    static_assert(sizeof(cpu_set_t) <= sizeof(saved_), "cpu_set_t does not fit");
    if (cpus.empty()) return;
    auto* saved = reinterpret_cast<cpu_set_t*>(saved_);
    if (sched_getaffinity(0, sizeof(cpu_set_t), saved) != 0) return;
    pinCurrentThread(cpus);
    active_ = true;
#else
    (void)cpus;
#endif
}

AffinityScope::~AffinityScope() {
#if defined(URAW_AFFINITY)
    if (active_) sched_setaffinity(0, sizeof(cpu_set_t), reinterpret_cast<cpu_set_t*>(saved_));
#endif
}

ThreadPool::~ThreadPool() {
    {
        std::lock_guard<std::mutex> lock(m_);
        stop_ = true;
    }
    cv_.notify_all();
    for (auto& t : workers_) t.join();
}

void ThreadPool::runJob(Job& job) {
    for (;;) {
        const size_t i = job.next.fetch_add(1, std::memory_order_relaxed);
        if (i >= job.count) return;
        (*job.fn)(i);
        if (job.done.fetch_add(1, std::memory_order_acq_rel) + 1 == job.count) {
            std::lock_guard<std::mutex> lock(job.m);
            job.cv.notify_all();
        }
    }
}

void ThreadPool::workerLoop(const std::vector<int>& cpus) {
#if defined(URAW_AFFINITY)
    if (!cpus.empty()) pinCurrentThread(cpus);
#else
    (void)cpus;
#endif
    for (;;) {
        std::shared_ptr<Job> job;
        {
            std::unique_lock<std::mutex> lock(m_);
            cv_.wait(lock, [this] { return stop_ || !jobs_.empty(); });
            if (stop_) return;
            job = jobs_.front();
            // Every index is claimed: drop the job from the queue.
            if (job->next.load(std::memory_order_relaxed) >= job->count) {
                jobs_.pop_front();
                continue;
            }
        }
        runJob(*job);
        std::lock_guard<std::mutex> lock(m_);
        if (!jobs_.empty() && jobs_.front() == job) jobs_.pop_front();
    }
}

void ThreadPool::parallelFor(size_t count, const std::function<void(size_t)>& fn) {
    if (count == 0) return;
    if (workers_.empty() || count == 1) {
        for (size_t i = 0; i < count; ++i) fn(i);
        return;
    }
    auto job = std::make_shared<Job>();
    job->fn = &fn;
    job->count = count;
    {
        std::lock_guard<std::mutex> lock(m_);
        jobs_.push_back(job);
    }
    cv_.notify_all();
    runJob(*job);
    {
        std::unique_lock<std::mutex> lock(job->m);
        job->cv.wait(lock, [&] { return job->done.load(std::memory_order_acquire) == count; });
    }
    std::lock_guard<std::mutex> lock(m_);
    auto it = std::find(jobs_.begin(), jobs_.end(), job);
    if (it != jobs_.end()) jobs_.erase(it);
}

}  // namespace uraw::detail
