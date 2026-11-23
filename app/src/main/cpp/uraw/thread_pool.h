#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace uraw::detail {

// Fixed set of worker threads. parallelFor may be called from several
// threads at once.
//
// With performanceCores, the workers are restricted to the fastest CPU
// cluster of a big.LITTLE system that fits them. Callers should hold an
// AffinityScope while they use the pool, so their own share of the work runs
// on the same cores. On systems with one kind of core (or where affinity is
// not available) this has no effect.
class ThreadPool {
public:
    explicit ThreadPool(int threads, bool performanceCores = false);
    ~ThreadPool();

    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator=(const ThreadPool&) = delete;

    // Runs fn(i) for every i in [0, count). Returns when all calls finished.
    void parallelFor(size_t count, const std::function<void(size_t)>& fn);

    int threads() const { return static_cast<int>(workers_.size()) + 1; }
    const std::vector<int>& cpus() const { return cpus_; }

    static int defaultThreads();

private:
    struct Job {
        const std::function<void(size_t)>* fn = nullptr;
        size_t count = 0;
        std::atomic<size_t> next{0};
        std::atomic<size_t> done{0};
        std::mutex m;
        std::condition_variable cv;
    };

    void workerLoop(const std::vector<int>& cpus);
    static void runJob(Job& job);

    std::vector<std::thread> workers_;
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<std::shared_ptr<Job>> jobs_;
    bool stop_ = false;
    std::vector<int> cpus_;
};

// Moves the calling thread onto `cpus` for the lifetime of the object and
// restores its previous affinity afterwards. No effect when cpus is empty.
class AffinityScope {
public:
    explicit AffinityScope(const std::vector<int>& cpus);
    ~AffinityScope();
    AffinityScope(const AffinityScope&) = delete;
    AffinityScope& operator=(const AffinityScope&) = delete;

private:
    bool active_ = false;
    alignas(8) unsigned char saved_[128];
};

// CPUs to run `threads` workers on (0 = all fast cores): the fastest single
// cluster that has enough cores, else the fastest clusters combined. The
// slowest cluster is excluded. Empty when all cores are alike or unknown.
std::vector<int> performanceCpus(int threads);

}  // namespace uraw::detail
