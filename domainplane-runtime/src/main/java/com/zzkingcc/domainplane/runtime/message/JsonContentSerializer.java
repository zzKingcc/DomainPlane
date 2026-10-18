package com.zzkingcc.domainplane.runtime.message;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.serializer.std.NullableObjectSerializer;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.List;

/**
 * 用户内容的单块编解码器（供图状态检查点序列化使用）。
 *
 * <p>为什么必须自己写，而不是用 langgraph4j 自带的 {@code ContentSerializer}：</p>
 *
 * <ul>
 *   <li>自带的那个写 base64 图时会对空值调 {@code writeUTF}，直接抛
 *       {@code NullPointerException}（已实测）；</li>
 *   <li>langchain4j 的消息类与内容类<b>全部不是</b> {@link java.io.Serializable}，
 *       未注册的 {@code Content} 会退回 Java 序列化并抛 {@code NotSerializableException}。</li>
 * </ul>
 *
 * <p>后果不是"少存一张图"，而是整轮对话失败：只要多模态消息进了图状态，
 * {@code CompiledGraph.cloneState} 深拷贝状态那一步就会炸。纯文本之所以一直没暴露，
 * 是因为单文本 {@code UserMessage} 走的是字符串旁路，压根不经过内容块。</p>
 *
 * <p>为什么借 langchain4j 自己的 JSON codec、而不是自己拼 JSON：会话记忆落 Redis
 * 走的正是 {@code ChatMessageSerializer}。两处共用同一套编解码，才不会出现
 * "记忆里读得回、检查点里读不回"这种只在断点续跑时才暴露的分叉。
 * 做法是把单块内容包成一条只含它的 {@link UserMessage} 再序列化，读时取回
 * {@code contents[0]} —— 借的是格式，不是实现。</p>
 *
 * @author zzkingcc
 */
public class JsonContentSerializer implements NullableObjectSerializer<Content> {

    @Override
    public void write(Content content, ObjectOutput out) throws IOException {
        out.writeUTF(ChatMessageSerializer.messagesToJson(List.of(UserMessage.from(content))));
    }

    @Override
    public Content read(ObjectInput in) throws IOException, ClassNotFoundException {
        String json = in.readUTF();
        List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
        if (messages.isEmpty() || !(messages.get(0) instanceof UserMessage user) || user.contents().isEmpty()) {
            throw new IOException("内容块反序列化失败，回读结果不含内容块：" + json);
        }
        return user.contents().get(0);
    }
}
