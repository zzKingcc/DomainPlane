package com.zzkingcc.domainplane.server.storage;

import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S3 协议族的 blob 存储：COS / OSS / MinIO / AWS S3 共用这一份实现。
 *
 * <p><b>为什么只做一套</b>：这几家的对象 API 都是 S3 兼容，差异只在 endpoint 与
 * 签名细节（极少数厂商要额外 header，用 {@code pathStyle} 与 endpoint 就覆盖了常见情形）。
 * 为每家复制一份驱动，代价是"加一个厂商 = 复制 200 行 + 各自修 bug"，而不是加几行配置。</p>
 *
 * <p><b>键的映射</b>：{@link BlobStore} 的键是相对路径（{@code kb/chunks/索引/docId.json}），
 * 落到桶里是 {@code [连接前缀/]key}。前缀来自连接配置，用于多环境共桶隔离；
 * 返回给调用方的键<b>永远是去前缀的相对键</b>，这样换连接（前缀不同）不需要改上层逻辑。</p>
 *
 * <p><b>异常姿态与本地实现对齐</b>：写入/删除失败抛 {@link UncheckedIOException}
 * （调用方按"存储故障"处理，不吞）；读取不存在返回空 Optional（区分"没有"与"坏了"）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class S3BlobStore implements BlobStore {

    /** 厂商没给 region 时的默认值：MinIO 与多数私有化部署只认 us-east-1 */
    private static final String DEFAULT_REGION = "us-east-1";

    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final String signature;

    public S3BlobStore(InfraSettings.ObjectStorage config) {
        if (config == null || !config.isUsable()) {
            throw new IllegalArgumentException("对象存储连接未配置完整（endpoint / bucket / AK / SK 都要填）");
        }
        String region = config.getRegion() == null || config.getRegion().isBlank()
                ? DEFAULT_REGION : config.getRegion().trim();
        this.bucket = config.getBucket().trim();
        this.prefix = config.effectivePrefix();
        this.signature = config.location();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(config.effectiveEndpoint()))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey().trim(),
                                config.getSecretKey().trim())))
                /* pathStyle：MinIO 与私有化部署通常只能走 endpoint/bucket；
                   AWS/COS/OSS 默认是 bucket.endpoint。开关放在连接配置里，不猜。 */
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.isPathStyle())
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        log.info("[blob 存储] S3 兼容实现已装配：{}（pathStyle={}，region={}）",
                config.describe(), config.isPathStyle(), region);
    }

    /** 连接配置的物理位置指纹：配置变了，注册表据此重建客户端 */
    public String signature() {
        return signature;
    }

    @Override
    public void put(String key, InputStream data) {
        if (data == null) {
            throw new IllegalArgumentException("字节流不能为空（key=" + key + "）");
        }
        try (InputStream in = data) {
            // 没有长度信息只能整体读进内存：知道大小的调用方应走三参重载
            put(key, in, -1);
        } catch (IOException e) {
            throw wrap("blob 写入", key, e);
        }
    }

    @Override
    public void put(String key, InputStream data, long size) {
        if (data == null) {
            throw new IllegalArgumentException("字节流不能为空（key=" + key + "）");
        }
        String objectKey = objectKey(key);
        try (InputStream in = data) {
            RequestBody body = size >= 0
                    ? RequestBody.fromInputStream(in, size)
                    : RequestBody.fromBytes(in.readAllBytes());
            client.putObject(b -> b.bucket(bucket).key(objectKey), body);
        } catch (Exception e) {
            throw wrap("blob 写入", key, e);
        }
    }

    @Override
    public Optional<InputStream> open(String key) {
        try {
            ResponseInputStream<GetObjectResponse> in = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
            return Optional.of(in);
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw wrap("blob 读取", key, e);
        } catch (Exception e) {
            throw wrap("blob 读取", key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(b -> b.bucket(bucket).key(objectKey(key)));
        } catch (Exception e) {
            throw wrap("blob 删除", key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
            return true;
        } catch (S3Exception e) {
            // 404 才是"没有"；403（凭证/策略）必须抛，否则会被当成"对象不存在"而误判缺档
            if (e.statusCode() == 404) {
                return false;
            }
            throw wrap("blob 存在性检查", key, e);
        } catch (Exception e) {
            throw wrap("blob 存在性检查", key, e);
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        String wanted = prefix == null ? "" : prefix.replace('\\', '/');
        String listPrefix = objectKey(wanted);
        List<String> keys = new ArrayList<>();
        try {
            /* 分页交给 SDK 的 paginator：对账要"列举全部档案"，手写 continuationToken
               容易在第一页就停住——那会让对账把其余档案全当孤儿 */
            for (S3Object object : client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(listPrefix).build())
                    .contents()) {
                String key = object.key();
                if (this.prefix.isEmpty()) {
                    keys.add(key);
                } else if (key.startsWith(this.prefix + "/")) {
                    keys.add(key.substring(this.prefix.length() + 1));
                }
            }
            keys.sort(String::compareTo);
            return keys;
        } catch (Exception e) {
            throw wrap("blob 列举（prefix=" + prefix + "）", "-", e);
        }
    }

    @Override
    public Optional<Instant> lastModified(String key) {
        try {
            return Optional.ofNullable(client.headObject(
                            HeadObjectRequest.builder().bucket(bucket).key(objectKey(key)).build())
                    .lastModified());
        } catch (Exception e) {
            // 与本地实现同口径：年龄只是回收护栏，取不到不算故障
            return Optional.empty();
        }
    }

    /**
     * 连通性探测（管控台「测试连接」用）：HEAD 桶。
     *
     * <p>为什么不 LIST：空桶 LIST 也是 200，看不出权限问题；HEAD 桶直接回答
     * "这个桶存不存在、这组凭证能不能碰它"。</p>
     */
    public boolean ping() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (Exception e) {
            log.warn("[blob 存储] S3 连通性探测失败（{}）: {}", signature, e.getMessage());
            return false;
        }
    }

    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("[blob 存储] 关闭 S3 客户端失败（{}）: {}", signature, e.getMessage());
        }
    }

    /** 相对键 → 桶里的对象键（加连接前缀） */
    private String objectKey(String key) {
        String k = key == null ? "" : key.replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return prefix.isEmpty() ? k : prefix + "/" + k;
    }

    /**
     * 统一包装成 {@link UncheckedIOException}。
     *
     * <p>AWS SDK 抛的是 {@code SdkException} 家族（不是 IOException），而
     * {@link UncheckedIOException} 的构造只收 IOException——把原异常原样塞进去
     * 编译不过，包一层又丢掉类型。用 {@link IOException#initCause} 保留原始异常，
     * 调用方 printStackTrace 时仍能追到 S3 的请求 id。</p>
     */
    private static UncheckedIOException wrap(String action, String key, Exception cause) {
        if (cause instanceof UncheckedIOException uio) {
            return uio;
        }
        IOException io = cause instanceof IOException existing ? existing : new IOException(cause);
        return new UncheckedIOException(action + "（key=" + key + "）: " + cause.getMessage(), io);
    }
}
