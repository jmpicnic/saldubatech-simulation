import matplotlib.pyplot as plt


def decorated_histogram(h_title, h_xlabel, h_data, bins=30, range=None):
    plt.figure(figsize=(10,10))
    plt.title(h_title)
    plt.xlabel(h_xlabel)
    plt.ylabel('Frequency')

    h_mean = h_data.mean()
    q95 = h_data.quantile(0.95)

    histogram = plt.hist(h_data, bins=bins, range=range)
    label = r'$\mu=%s,\ 95\%%=%s$'%(h_mean, q95)
    counts, bins, patches = histogram
    height = counts.max()*0.67
    plt.text(q95, height, label)
    for patch, leftside in zip(patches, bins[:-1]):
        if leftside > q95:
            patch.set_facecolor('red')
    return histogram