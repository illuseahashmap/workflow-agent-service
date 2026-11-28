"""Remove authoring labels without adding answer/source terms. Explicit repairs use existing question context."""
import re

REPAIRS = {
    'R400-ADM-d1d3cd0ba4-2': '样品冷藏柜温度报警了，除了写“已报修”，还要记录哪三项？',
    'R400-SEC-a54f1fd7d0-2': '我想分享一份带客户标识的访问日志，发出去前要核对什么？',
    'R400-HR-5a34212dc9-2': '给高空设备维护岗核验证书，具体要检查哪两个条件？',
    'R400-OPS-3636798744-2': '别的服务要求30分钟恢复，这个目标能直接拿来考核夜间批处理吗？',
    'R400-OPS-c5b285443d-2': '现在只知道磁盘用了70%，能判断明天会不会到80%吗？',
    'R400-OPS-cf3e8430f0-2': '做消息补偿前，除了检查原消息的状态，还要遵循什么要求？',
    'R400-HR-e47a09dbff-2': '岗位证书续期后，凭什么确认已经续好了？',
    'R400-OPS-e72ba7b1e6-2': '服务证书续期后，要检查哪些状态才算完成？',
    'R400-DEL-9daaa05833-2': '需求澄清的记录里需要写清哪三项？',
    'R400-LEG-581a31636a-2': '准备给合同对方发正式通知，应该先查哪两项信息？',
    'R400-LEG-893cc8b82f-2': '合同审查里还有保留意见，这些事项该怎么处理？',
    'R400-HR-6b791dd237-2': '员工培训结束后，需要哪些记录才能认定完课？',
    'R400-OPS-5ff50a6004-1': '缓存刷新任务成功了，就能证明所有用户都看到新值了吗？还有哪些业务验证要做？',
    'R400-SUP-b5b35ec8da-2': '恢复服务后要按每日还是每周回访，能挑省事的安排执行吗？',
    'R400-HR-0f12e4d5a1-2': '轮岗期限的两条规定不一样，可以取平均数，按五周执行吗？',
    'R400-SEC-b08059e482-2': '演练录像的两个留存期限不同，我能直接按短的期限清理吗？',
    'R400-LEG-661428d939-2': '保密审查的两份规定指定了不同的组，我随便找一个组审核行吗？',
    'R400-DEL-e4ed62e301-2': '验收抽样数量的两条规定不一样，能不等裁决就抽较少的20个结案吗？',
    'R400-ADM-ae92bb7355-2': '展厅两场活动之间的间隔要求有冲突，能按方便排期的30分钟直接订吗？',
    'R400-SAL-3c1a8d1393-2': '渠道返利规定有冲突，为了促成交易，我能先承诺较高的3%吗？',
    'R400-FIN-bf24e417be-2': '评审补贴的两份通知金额不同，能直接按金额更高的乙通知付款吗？',
    'R400-OPS-1e9fa64d9d-2': '维护时间的两份公告不一致，能因为周三那份排在后面就按它执行吗？',
    'R400-PROC-ab94bb68a1-2': '质保返修运费的两条规定不一致，能因为乙文件排在后面就让采购方承担吗？',
}


def naturalize(case):
    original = case['query'].removeprefix('仅依据冻结合成资料作答。')
    question = re.sub(r'^关于[^：:]+[：:]\s*', '', original)
    question = question.split(' 两条材料同级')[0].split(' 题面约定：')[0]
    question = question.replace('请同时说明规则与相关限制。', '').replace('请解释分类到对应细则的依据及最终判断所缺或所需的条件。', '').strip()
    question = REPAIRS.get(case['caseId'], question)
    if case['scenarioType'] == 'CONFLICT':
        question += ' 两份规定都有效、效力相同，写的又都是唯一要求，也没说谁优先。这种情况怎么办？'
    elif '请解释分类到对应细则' in original and case['caseId'] not in REPAIRS:
        question += ' 需要查哪些规定，具体怎么判断？'
    elif '请同时说明规则与相关限制' in original and case['caseId'] not in REPAIRS:
        question += ' 有什么条件或限制也说一下。'
    return question
