#pragma once

#include <vulkan/vulkan.h>
#include <algorithm>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

class RawUploadPool : public std::enable_shared_from_this<RawUploadPool> {
    struct Slot {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        VkDeviceSize capacity = 0;
        uint64_t generation = 0;
        uint32_t cpuOwners = 0;
        uint32_t gpuOwners = 0;
        bool coherent = false;
    };

public:
    class Lease {
        friend class RawUploadPool;
        std::shared_ptr<RawUploadPool> pool_;
        std::shared_ptr<Slot> slot_;
        uint64_t generation_ = 0;
        size_t bytes_ = 0;

    public:
        ~Lease() {
            std::lock_guard<std::mutex> lock(pool_->mutex_);
            --slot_->cpuOwners;
        }

        void* beginRead() {
            std::lock_guard<std::mutex> lock(pool_->mutex_);
            if (!pool_->valid(*this)) return nullptr;
            ++pool_->readers_;
            return slot_->mapped;
        }

        void endRead() {
            std::lock_guard<std::mutex> lock(pool_->mutex_);
            --pool_->readers_;
            pool_->readersDone_.notify_all();
        }

        size_t size() const { return bytes_; }
    };

    class GpuUse {
        friend class RawUploadPool;
        std::shared_ptr<RawUploadPool> pool_;
        std::shared_ptr<Slot> slot_;

    public:
        ~GpuUse() {
            std::lock_guard<std::mutex> lock(pool_->mutex_);
            --slot_->gpuOwners;
        }
        VkBuffer buffer() const { return slot_->buffer; }
        uint64_t generation() const { return slot_->generation; }
    };

    void attach(VkPhysicalDevice physicalDevice, VkDevice device) {
        std::lock_guard<std::mutex> lock(mutex_);
        device_ = device;
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties_);
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice, &properties);
        maxBytes_ = properties.limits.maxStorageBufferRange;
        accepting_ = true;
    }

    // The owner first waits for GPU work and releases its GpuUse objects.
    // Queued CPU leases become invalid; active CPU reads finish before unmapping.
    void detach() {
        std::unique_lock<std::mutex> lock(mutex_);
        accepting_ = false;
        readersDone_.wait(lock, [&] { return readers_ == 0; });
        for (auto& slot : slots_) destroy(*slot);
        slots_.clear();
        device_ = VK_NULL_HANDLE;
    }

    std::unique_ptr<Lease> copy(const void* source, size_t sourceBytes, size_t rowLayoutBytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!accepting_ || !source || sourceBytes == 0 || rowLayoutBytes == 0 || rowLayoutBytes > maxBytes_) return {};
        const VkDeviceSize alignedBytes = (static_cast<VkDeviceSize>(rowLayoutBytes) + 3u) & ~VkDeviceSize{3u};
        if (alignedBytes > maxBytes_) return {};
        std::shared_ptr<Slot> available;
        for (auto& slot : slots_) {
            if (slot->cpuOwners == 0 && slot->gpuOwners == 0) {
                if (!available || slot->capacity >= alignedBytes) available = slot;
                if (slot->capacity >= alignedBytes) break;
            }
        }
        // Two submitted frames, one active CPU frame and one pending CPU frame.
        if (!available && slots_.size() < 4u) {
            available = std::make_shared<Slot>();
            slots_.push_back(available);
        }
        if (!available) return {};
        if (available->capacity < alignedBytes && !allocate(*available, alignedBytes)) return {};
        const size_t copiedBytes = std::min(sourceBytes, rowLayoutBytes);
        std::memcpy(available->mapped, source, copiedBytes);
        if (alignedBytes > copiedBytes) {
            std::memset(static_cast<std::byte*>(available->mapped) + copiedBytes, 0,
                static_cast<size_t>(alignedBytes - copiedBytes));
        }
        if (!available->coherent) {
            VkMappedMemoryRange flush{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
            flush.memory = available->memory;
            flush.size = VK_WHOLE_SIZE;
            if (vkFlushMappedMemoryRanges(device_, 1, &flush) != VK_SUCCESS) return {};
        }
        auto lease = std::make_unique<Lease>();
        lease->pool_ = shared_from_this();
        lease->slot_ = available;
        lease->generation_ = available->generation;
        lease->bytes_ = copiedBytes;
        ++available->cpuOwners;
        return lease;
    }

    std::unique_ptr<GpuUse> acquire(const Lease& lease, VkDevice device, size_t minimumBytes, size_t layoutBytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (lease.pool_.get() != this || device != device_ || !valid(lease) ||
            lease.bytes_ < minimumBytes || lease.slot_->capacity < layoutBytes) return {};
        auto use = std::make_unique<GpuUse>();
        use->pool_ = shared_from_this();
        use->slot_ = lease.slot_;
        ++lease.slot_->gpuOwners;
        return use;
    }

private:
    bool valid(const Lease& lease) const {
        return accepting_ && lease.slot_->mapped && lease.generation_ == lease.slot_->generation;
    }

    void destroy(Slot& slot) {
        if (slot.mapped) vkUnmapMemory(device_, slot.memory);
        if (slot.buffer) vkDestroyBuffer(device_, slot.buffer, nullptr);
        if (slot.memory) vkFreeMemory(device_, slot.memory, nullptr);
        slot.mapped = nullptr;
        slot.buffer = VK_NULL_HANDLE;
        slot.memory = VK_NULL_HANDLE;
        slot.capacity = 0;
    }

    bool allocate(Slot& slot, VkDeviceSize bytes) {
        destroy(slot);
        VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        info.size = bytes;
        info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &info, nullptr, &slot.buffer) != VK_SUCCESS) return false;
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device_, slot.buffer, &requirements);
        uint32_t memoryType = UINT32_MAX;
        for (uint32_t i = 0; i < memoryProperties_.memoryTypeCount; ++i) {
            const auto flags = memoryProperties_.memoryTypes[i].propertyFlags;
            if ((requirements.memoryTypeBits & (1u << i)) && (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                memoryType = i;
                if (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) break;
            }
        }
        if (memoryType == UINT32_MAX) { destroy(slot); return false; }
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memoryType;
        if (vkAllocateMemory(device_, &allocation, nullptr, &slot.memory) != VK_SUCCESS ||
            vkBindBufferMemory(device_, slot.buffer, slot.memory, 0) != VK_SUCCESS ||
            vkMapMemory(device_, slot.memory, 0, VK_WHOLE_SIZE, 0, &slot.mapped) != VK_SUCCESS) {
            destroy(slot);
            return false;
        }
        slot.capacity = bytes;
        slot.coherent = (memoryProperties_.memoryTypes[memoryType].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        slot.generation = ++nextGeneration_;
        return true;
    }

    std::mutex mutex_;
    std::condition_variable readersDone_;
    VkDevice device_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProperties_{};
    VkDeviceSize maxBytes_ = 0;
    uint64_t nextGeneration_ = 0;
    uint32_t readers_ = 0;
    bool accepting_ = false;
    std::vector<std::shared_ptr<Slot>> slots_;
};
