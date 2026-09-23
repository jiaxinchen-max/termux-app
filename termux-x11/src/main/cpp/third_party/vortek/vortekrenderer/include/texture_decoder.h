#ifndef VORTEK_TEXTURE_DECODER_H
#define VORTEK_TEXTURE_DECODER_H

#include "vortek.h"

typedef struct TextureDecoder_Image {
    VkImage image;
    VkFormat format;
    short width;
    short height;
} TextureDecoder_Image;

typedef struct TextureDecoder_BoundBuffer {
    VkBuffer buffer;
    VkDeviceSize memoryOffset;
    ResourceMemory* memory;
} TextureDecoder_BoundBuffer;

typedef struct TextureDecoder_BufferImageCopy {
    TextureDecoder_BoundBuffer* srcBuffer;
    TextureDecoder_Image* dstImage;
    VkImageLayout dstImageLayout;
    VkBufferImageCopy region;
} TextureDecoder_BufferImageCopy;

typedef struct TextureDecoder {
    SparseArray64 images;
    SparseArray64 boundBuffers;
    ArrayDeque bufferImageCopies;
    short imageCacheSize;
    ThreadPool* threadPool;
    uint32_t deviceSupportedFormats;

    VkDevice device;
    VkQueue queue;
    VkCommandPool commandPool;
    VkCommandBuffer commandBuffer;
    VkFence fence;

    VkBuffer buffer;
    VkDeviceMemory memory;
    uint32_t bufferSize;
    void* decompressedData;
} TextureDecoder;

extern TextureDecoder* TextureDecoder_create(VkContext* context, VkPhysicalDevice physicalDevice, VkDevice device, VkPhysicalDeviceFeatures* supportedFeatures);
extern void TextureDecoder_destroy(TextureDecoder* textureDecoder);
extern void TextureDecoder_decodeAll(TextureDecoder* textureDecoder);
extern void TextureDecoder_copyBufferToImage(TextureDecoder* textureDecoder, VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkImage dstImage, VkImageLayout dstImageLayout, uint32_t regionCount, VkBufferImageCopy* regions);
extern void TextureDecoder_copyBufferToImage2(TextureDecoder* textureDecoder, VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkImage dstImage, VkImageLayout dstImageLayout, VkCopyBufferToImageInfo2* copyBufferToImageInfo);
extern bool TextureDecoder_containsImage(TextureDecoder* textureDecoder, VkImage image);
extern VkResult TextureDecoder_createImage(TextureDecoder* textureDecoder, VkImageCreateInfo* imageInfo, VkImage* pImage);
extern void TextureDecoder_destroyImage(TextureDecoder* textureDecoder, VkImage image);
extern void TextureDecoder_addBoundBuffer(TextureDecoder* textureDecoder, ResourceMemory* resourceMemory, VkBuffer buffer, VkDeviceSize memoryOffset);
extern void TextureDecoder_removeBoundBuffer(TextureDecoder* textureDecoder, VkBuffer buffer);
extern bool TextureDecoder_isFormatSupported(TextureDecoder* textureDecoder, VkFormat format);
extern bool isCompressedFormat(VkFormat format);
extern VkResult getCompressedImageFormatProperties(VkFormat format, VkImageFormatProperties* pImageFormatProperties);

#endif

#define DECOMPRESSED_FORMAT VK_FORMAT_B8G8R8A8_UNORM