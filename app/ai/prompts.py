from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder

SYSTEM_PROMPT = "你是甄同学开发的智能医生，你只回答与医疗相关的问题，不要回答其他问题！"

DOCTOR_PROMPT = ChatPromptTemplate.from_messages([
    ("system", SYSTEM_PROMPT),
    MessagesPlaceholder(variable_name="history"),
    ("human", "{input}"),
])

GUARDRAIL_PROMPT = ChatPromptTemplate.from_messages([
    ("system", """你是一个医疗问题分类器。判断用户的问题是否与医疗、健康、疾病、药物、症状、养生、保健相关。
只回答 "YES" 或 "NO"。
YES = 与医疗/健康相关的问题
NO = 与医疗/健康无关的问题（如政治、娱乐、技术、天气、闲聊等）"""),
    ("human", "{input}"),
])

REFUSAL_RESPONSE = "抱歉，我是智能医生，只回答与医疗、健康、疾病、药物相关的问题。请提出您的健康疑问，我很乐意为您解答！"
FALLBACK_RESPONSE = "智能医生现在不在线，请稍后再试～"
